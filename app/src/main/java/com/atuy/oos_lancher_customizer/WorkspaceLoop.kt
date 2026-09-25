package com.atuy.oos_lancher_customizer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Optional wrap-around paging for the home-screen Workspace.
 *
 * Oplus uses two independent scrolling engines at the workspace edge: PagedView.mScroller and
 * OplusPagedViewImpl.mSpringOverScroller. A wrap stops both before changing pages.
 *
 * The first and last pages are not adjacent in PagedView's real coordinate space, so using
 * snapToPage() for the wrap would animate through every intermediate page. Instead, the Workspace
 * itself exits one screen in the swipe direction, the destination page is switched while offscreen,
 * and the Workspace re-enters from the opposite side.
 */
class WorkspaceLoop : XposedModule() {

    companion object {
        private const val TAG = "OOS16_WorkspaceLoop"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val PAGED_VIEW_CLASS = "com.android.launcher3.PagedView"
        private const val WORKSPACE_CLASS = "com.android.launcher3.OplusWorkspace"

        private const val WRAP_EXIT_DURATION_MS = 110L
        private const val WRAP_ENTER_DURATION_MS = 180L
    }

    private data class TouchStart(
        val x: Float,
        val y: Float,
        val page: Int
    )

    @Volatile
    private var hooksInstalled = false

    @Volatile
    private var loopEnabled = false

    private var remotePrefs: SharedPreferences? = null
    private val touchStarts = Collections.synchronizedMap(WeakHashMap<Any, TouchStart>())
    private val animatingViews = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )

    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == ModulePrefs.KEY_WORKSPACE_LOOP) {
                loopEnabled = prefs.getBoolean(ModulePrefs.KEY_WORKSPACE_LOOP, false)
                moduleLog("workspace loop changed: $loopEnabled")
            }
        }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE || hooksInstalled) return

        runCatching {
            attachPreferences()
            installHooks(param.defaultClassLoader)
            hooksInstalled = true
            moduleLog("workspace loop hooks installed; enabled=$loopEnabled")
        }.onFailure {
            moduleLog("critical: ${it.message}", it)
        }
    }

    private fun attachPreferences() {
        remotePrefs = runCatching {
            getRemotePreferences(ModulePrefs.GROUP)
        }.onFailure {
            moduleLog("remote preferences unavailable; loop defaults off: ${it.message}")
        }.getOrNull()

        remotePrefs?.let { prefs ->
            loopEnabled = prefs.getBoolean(ModulePrefs.KEY_WORKSPACE_LOOP, false)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        val pagedViewClass = Class.forName(PAGED_VIEW_CLASS, false, classLoader)
        val workspaceClass = Class.forName(WORKSPACE_CLASS, false, classLoader)

        val workspaceTouch = workspaceClass.getDeclaredMethod(
            "onTouchEvent",
            MotionEvent::class.java
        ).apply { isAccessible = true }

        hook(workspaceTouch)
            .setId("workspace-loop-touch")
            .setPriority(XposedInterface.PRIORITY_HIGHEST)
            .intercept { chain ->
                val owner = chain.thisObject
                val event = chain.args[0] as? MotionEvent
                if (owner == null || event == null) {
                    return@intercept chain.proceed()
                }

                val workspaceView = owner as? View
                if (workspaceView != null && animatingViews.contains(workspaceView)) {
                    touchStarts.remove(owner)
                    return@intercept true
                }

                if (!loopEnabled) {
                    touchStarts.remove(owner)
                    return@intercept chain.proceed()
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val page = invokeIntNoArg(owner, "getCurrentPage") ?: 0
                        touchStarts[owner] = TouchStart(event.x, event.y, page)
                        chain.proceed()
                    }

                    MotionEvent.ACTION_UP -> {
                        val start = touchStarts.remove(owner)
                        val wasBeingDragged = readField(owner, "mIsBeingDragged") == true
                        val wrapTarget =
                            if (wasBeingDragged && start != null) {
                                calculateTouchWrapTarget(owner, event, start)
                            } else {
                                null
                            }

                        // Let Oplus finish its normal UP handling first. This releases the
                        // velocity tracker and closes the normal page-drag interaction.
                        val result = chain.proceed()

                        if (
                            wrapTarget != null &&
                            start != null &&
                            stillAtSameEdge(owner, start, wrapTarget)
                        ) {
                            val direction = if (wrapTarget == 0) -1 else 1
                            animateWrap(owner, wrapTarget, "touch", direction)
                        }
                        result
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        touchStarts.remove(owner)
                        chain.proceed()
                    }

                    else -> chain.proceed()
                }
            }

        hookEdgeNavigation(pagedViewClass, workspaceClass, "scrollLeft", wrapToLast = true)
        hookEdgeNavigation(pagedViewClass, workspaceClass, "scrollRight", wrapToLast = false)
        moduleLog("hooked OplusWorkspace touch and animated edge navigation")
    }

    private fun calculateTouchWrapTarget(
        owner: Any,
        event: MotionEvent,
        start: TouchStart
    ): Int? {
        val pageCount = invokeIntNoArg(owner, "getPageCount") ?: return null
        if (pageCount <= 1) return null

        val last = validatedLastPage(owner, pageCount)
        val dx = event.x - start.x
        val dy = event.y - start.y
        val density = (owner as? View)?.resources?.displayMetrics?.density ?: 1f
        val pageSlop = (readField(owner, "mPageSlop") as? Number)?.toFloat() ?: 0f
        val threshold = max(32f * density, pageSlop)

        if (abs(dx) < threshold || abs(dx) <= abs(dy)) return null

        return when {
            start.page <= 0 && dx > 0f -> last
            start.page >= last && dx < 0f -> 0
            else -> null
        }
    }

    private fun stillAtSameEdge(owner: Any, start: TouchStart, target: Int): Boolean {
        val current = invokeIntNoArg(owner, "getCurrentPage") ?: return false
        val pageCount = invokeIntNoArg(owner, "getPageCount") ?: return false
        val last = validatedLastPage(owner, pageCount)

        return if (target == 0) {
            start.page >= last && current >= last
        } else {
            start.page <= 0 && current <= 0
        }
    }

    private fun hookEdgeNavigation(
        pagedViewClass: Class<*>,
        workspaceClass: Class<*>,
        methodName: String,
        wrapToLast: Boolean
    ) {
        val method = pagedViewClass.getDeclaredMethod(methodName).apply { isAccessible = true }
        hook(method)
            .setId("workspace-loop-$methodName")
            .setPriority(XposedInterface.PRIORITY_HIGHEST)
            .intercept { chain ->
                val owner = chain.thisObject
                if (!loopEnabled || owner == null || !workspaceClass.isInstance(owner)) {
                    return@intercept chain.proceed()
                }

                val workspaceView = owner as? View
                if (workspaceView != null && animatingViews.contains(workspaceView)) {
                    return@intercept true
                }

                val pageCount = invokeIntNoArg(owner, "getPageCount")
                    ?: return@intercept chain.proceed()
                if (pageCount <= 1) return@intercept chain.proceed()

                val current = invokeIntNoArg(owner, "getNextPage")
                    ?.takeIf { it >= 0 }
                    ?: invokeIntNoArg(owner, "getCurrentPage")
                    ?: return@intercept chain.proceed()

                val last = validatedLastPage(owner, pageCount)
                val atEdge = if (wrapToLast) current <= 0 else current >= last
                if (!atEdge) return@intercept chain.proceed()

                val target = if (wrapToLast) last else 0
                val direction = if (wrapToLast) 1 else -1
                if (animateWrap(owner, target, methodName, direction)) true else chain.proceed()
            }
    }

    private fun animateWrap(
        owner: Any,
        target: Int,
        source: String,
        direction: Int
    ): Boolean {
        val view = owner as? View ?: return wrapTo(owner, target, source)
        if (!animatingViews.add(view)) return true

        val width = max(view.width, view.measuredWidth).toFloat()
        if (width <= 0f) {
            animatingViews.remove(view)
            return wrapTo(owner, target, source)
        }

        return runCatching {
            cancelOplusScrollState(owner)

            val baseTranslation = view.translationX
            val exitTranslation = baseTranslation + (direction * width)
            val enterTranslation = baseTranslation - (direction * width)

            // We own Workspace.translationX only for the duration of this wrap.
            view.animate().cancel()

            var exitCancelled = false
            view.animate()
                .translationX(exitTranslation)
                .setDuration(WRAP_EXIT_DURATION_MS)
                .setInterpolator(AccelerateInterpolator(1.35f))
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        exitCancelled = true
                        cleanupWrapAnimation(view, baseTranslation)
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        view.animate().setListener(null)
                        if (exitCancelled) return

                        if (!wrapTo(owner, target, source)) {
                            cleanupWrapAnimation(view, baseTranslation)
                            return
                        }

                        // The old edge page is now fully outside the viewport. Place the
                        // destination one screen beyond the opposite edge and slide it in.
                        view.translationX = enterTranslation

                        var enterCancelled = false
                        view.animate()
                            .translationX(baseTranslation)
                            .setDuration(WRAP_ENTER_DURATION_MS)
                            .setInterpolator(DecelerateInterpolator(1.35f))
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationCancel(animation: Animator) {
                                    enterCancelled = true
                                    cleanupWrapAnimation(view, baseTranslation)
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    view.animate().setListener(null)
                                    if (enterCancelled) return
                                    view.translationX = baseTranslation
                                    animatingViews.remove(view)
                                    moduleLog("wrap animation complete via $source -> page $target")
                                }
                            })
                            .start()
                    }
                })
                .start()

            moduleLog("wrap animation started via $source -> page $target direction=$direction")
            true
        }.getOrElse {
            cleanupWrapAnimation(view, view.translationX)
            moduleLog("wrap animation failed via $source: ${it.message}", it)
            false
        }
    }

    private fun cleanupWrapAnimation(view: View, baseTranslation: Float) {
        view.animate().setListener(null)
        view.translationX = baseTranslation
        animatingViews.remove(view)
    }

    private fun validatedLastPage(owner: Any, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return invokeIntOneArg(owner, "validateNewPage", pageCount - 1) ?: (pageCount - 1)
    }

    private fun wrapTo(owner: Any, target: Int, source: String): Boolean {
        return runCatching {
            cancelOplusScrollState(owner)

            val previous = invokeIntNoArg(owner, "getCurrentPage") ?: -1
            val setPage = findMethod(
                owner,
                "setCurrentPage",
                Int::class.javaPrimitiveType!!
            ) ?: return false
            setPage.invoke(owner, target)

            val targetScroll = invokeIntOneArg(owner, "getScrollForPage", target)
            if (targetScroll != null) {
                invokeVoidTwoInts(owner, "scrollTo", targetScroll, 0)
                writeField(owner, "mUnboundedScroll", targetScroll)
                writeField(owner, "mLastScrollX", targetScroll)
            }

            writeField(owner, "mDiffScrollX", 0)
            writeField(owner, "mLastAmount", 0)
            writeField(owner, "mTempAmount", 0)
            writeField(owner, "mOverScrollAmount", 0)
            writeField(owner, "wasInOverscroll", false)
            writeField(owner, "mNextPage", -1)

            moduleLog("wrapped via $source: $previous -> $target")
            true
        }.getOrElse {
            moduleLog("wrap failed via $source: ${it.message}", it)
            false
        }
    }

    private fun cancelOplusScrollState(owner: Any) {
        invokeVoidNoArg(owner, "abortScrollerAnimation")

        val springScroller = readField(owner, "mSpringOverScroller")
        if (springScroller != null) {
            invokeVoidNoArg(springScroller, "abortAnimation")
        }

        writeField(owner, "mScrollMode", -1)
        writeField(owner, "mNextPage", -1)
        writeField(owner, "mIsBeingDragged", false)
    }

    private fun invokeIntNoArg(owner: Any, name: String): Int? {
        val method = findMethod(owner, name) ?: return null
        return runCatching { (method.invoke(owner) as? Number)?.toInt() }.getOrNull()
    }

    private fun invokeIntOneArg(owner: Any, name: String, value: Int): Int? {
        val method = findMethod(owner, name, Int::class.javaPrimitiveType!!) ?: return null
        return runCatching { (method.invoke(owner, value) as? Number)?.toInt() }.getOrNull()
    }

    private fun invokeVoidNoArg(owner: Any, name: String) {
        val method = findMethod(owner, name) ?: return
        runCatching { method.invoke(owner) }
    }

    private fun invokeVoidTwoInts(owner: Any, name: String, first: Int, second: Int) {
        val method = findMethod(
            owner,
            name,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) ?: return
        runCatching { method.invoke(owner, first, second) }
    }

    private fun findField(owner: Any, name: String): Field? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val field = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) return field
            cls = current.superclass
        }
        return null
    }

    private fun readField(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return runCatching { findField(owner, name)?.get(owner) }.getOrNull()
    }

    private fun writeField(owner: Any, name: String, value: Any?) {
        val field = findField(owner, name) ?: return
        runCatching { field.set(owner, value) }
    }

    private fun findMethod(owner: Any, name: String, vararg params: Class<*>): Method? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val method = runCatching {
                current.getDeclaredMethod(name, *params).apply { isAccessible = true }
            }.getOrNull()
            if (method != null) return method
            cls = current.superclass
        }
        return null
    }

    private fun moduleLog(msg: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log(Log.INFO, TAG, msg)
        } else {
            log(Log.ERROR, TAG, msg, throwable)
        }
    }
}
