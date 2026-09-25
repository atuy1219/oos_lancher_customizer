package com.atuy.oos_lancher_customizer

import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Optional wrap-around paging for the home-screen Workspace.
 *
 * The wrap animation is not implemented by this module. Instead, the destination page is
 * temporarily placed one logical screen beyond the current edge, then Oplus Launcher's own
 * snapToPage() path performs the transition using its normal OverScroller, interpolator,
 * page transition callbacks, effects, and page-indicator updates.
 */
class WorkspaceLoop : XposedModule() {

    companion object {
        private const val TAG = "OOS16_WorkspaceLoop"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val PAGED_VIEW_CLASS = "com.android.launcher3.PagedView"
        private const val WORKSPACE_CLASS = "com.android.launcher3.OplusWorkspace"
    }

    private data class TouchStart(
        val x: Float,
        val y: Float,
        val page: Int
    )

    private data class MovedPage(
        val view: View,
        val originalLeft: Int
    )

    private data class NativeWrapState(
        val source: String,
        val targetPage: Int,
        val realTargetScroll: Int,
        val originalMinScroll: Int,
        val originalMaxScroll: Int,
        val pageScrolls: IntArray,
        val originalPageScrolls: IntArray,
        val movedPages: List<MovedPage>
    )

    @Volatile
    private var hooksInstalled = false

    @Volatile
    private var loopEnabled = false

    private var remotePrefs: SharedPreferences? = null

    private val touchStarts =
        Collections.synchronizedMap(WeakHashMap<Any, TouchStart>())

    private val activeWraps =
        Collections.synchronizedMap(WeakHashMap<Any, NativeWrapState>())

    /**
     * Only populated while Oplus' own ACTION_UP code is choosing a destination page.
     * This lets the first native fling/snap target the wrapped page instead of starting
     * an edge spring that we would have to cancel and restart one frame later.
     */
    private val destinationOverrides =
        Collections.synchronizedMap(WeakHashMap<Any, Int>())

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

                if (activeWraps.containsKey(owner)) {
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

                        val prepared =
                            if (
                                wrapTarget != null &&
                                start != null &&
                                stillAtSameEdge(owner, start, wrapTarget)
                            ) {
                                // Prepare the opposite edge before Oplus handles ACTION_UP.
                                // Its own springBack/fling/snap code now sees this page as the
                                // adjacent destination, so we keep the original gesture velocity
                                // and never start a second animation.
                                prepareNativeWrap(owner, wrapTarget, "touch")
                            } else {
                                null
                            }

                        if (prepared != null) {
                            destinationOverrides[owner] = wrapTarget!!
                        }

                        val result = try {
                            chain.proceed()
                        } catch (error: Throwable) {
                            destinationOverrides.remove(owner)
                            if (prepared != null && activeWraps.remove(owner) === prepared) {
                                restoreNativeWrap(owner, prepared)
                            }
                            throw error
                        } finally {
                            destinationOverrides.remove(owner)
                        }

                        if (
                            prepared != null &&
                            activeWraps[owner] === prepared &&
                            !hasNativeTransitionStarted(owner, prepared.targetPage)
                        ) {
                            // Defensive fallback for launcher variants that do not start a
                            // destination snap from this ACTION_UP path.
                            activeWraps.remove(owner)
                            restoreNativeWrap(owner, prepared)
                            startNativeWrap(owner, prepared.targetPage, "touch-fallback")
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

        pagedViewClass.declaredMethods
            .filter { method ->
                method.name == "getDestinationPage" &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                val signature = method.parameterTypes.joinToString(",") { it.name }
                hook(method)
                    .setId("workspace-loop-destination($signature)")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { chain ->
                        val owner = chain.thisObject
                        val override = owner?.let { destinationOverrides[it] }
                        if (override != null) {
                            override
                        } else {
                            chain.proceed()
                        }
                    }
            }

        // PagedView.pageEndTransition() invokes this virtual method after mCurrentPage has
        // already been switched. Restore the real coordinates before OplusWorkspace runs its
        // own transition-end bookkeeping, so the launcher only observes a normal final state.
        val workspacePageEnd = workspaceClass.getDeclaredMethod(
            "onPageEndTransition"
        ).apply { isAccessible = true }

        hook(workspacePageEnd)
            .setId("workspace-loop-native-page-end")
            .setPriority(XposedInterface.PRIORITY_HIGHEST)
            .intercept { chain ->
                chain.thisObject?.let { owner ->
                    finishNativeWrap(owner)
                }
                chain.proceed()
            }

        hookEdgeNavigation(pagedViewClass, workspaceClass, "scrollLeft", wrapToLast = true)
        hookEdgeNavigation(pagedViewClass, workspaceClass, "scrollRight", wrapToLast = false)

        moduleLog("hooked OplusWorkspace native wrap animation")
    }

    private fun calculateTouchWrapTarget(
        owner: Any,
        event: MotionEvent,
        start: TouchStart
    ): Int? {
        val pageCount = invokeIntNoArg(owner, "getPageCount") ?: return null
        if (pageCount <= 1) return null

        val last = validatedLastPage(owner, pageCount)
        if (last <= 0) return null

        val dx = event.x - start.x
        val dy = event.y - start.y
        val density = (owner as? View)?.resources?.displayMetrics?.density ?: 1f
        val pageSlop = (readField(owner, "mPageSlop") as? Number)?.toFloat() ?: 0f
        val threshold = max(32f * density, pageSlop)

        if (abs(dx) < threshold || abs(dx) <= abs(dy)) return null

        // This supplied OplusLauncher build reports isPageOrderFlipped() == false.
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

                if (activeWraps.containsKey(owner)) {
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
                if (last <= 0) return@intercept chain.proceed()

                val atEdge = if (wrapToLast) current <= 0 else current >= last
                if (!atEdge) return@intercept chain.proceed()

                val target = if (wrapToLast) last else 0
                if (startNativeWrap(owner, target, methodName)) true else chain.proceed()
            }
    }

    private fun startNativeWrap(
        owner: Any,
        target: Int,
        source: String
    ): Boolean {
        if (activeWraps.containsKey(owner)) return true

        cancelOplusScrollState(owner)

        val state = prepareNativeWrap(owner, target, source) ?: return false

        val snapped = invokeBooleanOneInt(owner, "snapToPage", target) ?: false
        if (!snapped) {
            activeWraps.remove(owner)
            restoreNativeWrap(owner, state)
            moduleLog("native snap rejected via $source")
            return false
        }

        moduleLog("native wrap started via $source -> page $target")
        return true
    }

    private fun prepareNativeWrap(
        owner: Any,
        target: Int,
        source: String
    ): NativeWrapState? {
        if (activeWraps.containsKey(owner)) return activeWraps[owner]

        val workspace = owner as? View ?: return null
        val pageCount = invokeIntNoArg(owner, "getPageCount") ?: return null
        val panelCount = max(1, invokeIntNoArg(owner, "getPanelCount") ?: 1)
        val currentPage = invokeIntNoArg(owner, "getCurrentPage") ?: return null
        val lastPage = validatedLastPage(owner, pageCount)
        if (lastPage <= 0) return null

        val realCurrentScroll = invokeIntOneArg(owner, "getScrollForPage", currentPage)
            ?: return null
        val realTargetScroll = invokeIntOneArg(owner, "getScrollForPage", target)
            ?: return null

        val innerPage = if (target == 0) {
            max(0, currentPage - panelCount)
        } else {
            min(lastPage, currentPage + panelCount)
        }

        val innerScroll = invokeIntOneArg(owner, "getScrollForPage", innerPage)
            ?: realCurrentScroll

        var oneScreen = abs(realCurrentScroll - innerScroll)
        if (oneScreen <= 0) {
            oneScreen = max(workspace.width, workspace.measuredWidth)
        }
        if (oneScreen <= 0) return null

        // Last -> first continues towards increasing scroll; first -> last continues towards
        // decreasing scroll. The destination becomes a temporary adjacent page in that direction.
        val virtualTargetScroll =
            if (target == 0) {
                realCurrentScroll + oneScreen
            } else {
                realCurrentScroll - oneScreen
            }

        val offset = virtualTargetScroll - realTargetScroll
        if (offset == 0) return null

        val pageScrolls = readField(owner, "mPageScrolls") as? IntArray ?: return null
        if (target !in pageScrolls.indices) return null

        val originalPageScrolls = pageScrolls.clone()
        val movedPages = ArrayList<MovedPage>(panelCount)

        val lastTargetIndex = min(pageCount - 1, target + panelCount - 1)
        for (index in target..lastTargetIndex) {
            if (index in pageScrolls.indices) {
                pageScrolls[index] = originalPageScrolls[index] + offset
            }

            val pageView =
                invokeViewOneInt(owner, "getPageAt", index)
                    ?: invokeViewOneInt(owner, "getChildAt", index)
            if (pageView != null) {
                movedPages += MovedPage(pageView, pageView.left)
                pageView.offsetLeftAndRight(offset)
            }
        }

        val originalMin = (readField(owner, "mMinScroll") as? Number)?.toInt()
            ?: return restorePreparedWrapAndNull(
                owner,
                pageScrolls,
                originalPageScrolls,
                movedPages
            )
        val originalMax = (readField(owner, "mMaxScroll") as? Number)?.toInt()
            ?: return restorePreparedWrapAndNull(
                owner,
                pageScrolls,
                originalPageScrolls,
                movedPages
            )

        var virtualMin = virtualTargetScroll
        var virtualMax = virtualTargetScroll
        for (index in target..lastTargetIndex) {
            if (index in pageScrolls.indices) {
                virtualMin = min(virtualMin, pageScrolls[index])
                virtualMax = max(virtualMax, pageScrolls[index])
            }
        }

        writeField(owner, "mMinScroll", min(originalMin, virtualMin))
        writeField(owner, "mMaxScroll", max(originalMax, virtualMax))

        val state = NativeWrapState(
            source = source,
            targetPage = target,
            realTargetScroll = realTargetScroll,
            originalMinScroll = originalMin,
            originalMaxScroll = originalMax,
            pageScrolls = pageScrolls,
            originalPageScrolls = originalPageScrolls,
            movedPages = movedPages
        )
        activeWraps[owner] = state

        workspace.invalidate()
        movedPages.forEach { it.view.invalidate() }

        moduleLog(
            "native wrap prepared via $source: current=$currentPage target=$target " +
                "virtualTarget=$virtualTargetScroll offset=$offset"
        )
        return state
    }

    private fun restorePreparedWrapAndNull(
        owner: Any,
        pageScrolls: IntArray,
        originalPageScrolls: IntArray,
        movedPages: List<MovedPage>
    ): NativeWrapState? {
        if (pageScrolls.size == originalPageScrolls.size) {
            originalPageScrolls.copyInto(pageScrolls)
        }
        movedPages.forEach { moved ->
            moved.view.offsetLeftAndRight(moved.originalLeft - moved.view.left)
        }
        (owner as? View)?.invalidate()
        return null
    }

    private fun hasNativeTransitionStarted(owner: Any, target: Int): Boolean {
        val nextPage = invokeIntNoArg(owner, "getNextPage")
        if (nextPage == target) return true

        val inTransition = invokeBooleanNoArg(owner, "isPageInTransition")
        if (inTransition == true) return true

        val scrollFinished = invokeBooleanNoArg(owner, "isScrollFinished")
        return scrollFinished == false
    }

    private fun finishNativeWrap(owner: Any) {
        val state = activeWraps.remove(owner) ?: return
        restoreNativeWrap(owner, state)
        moduleLog(
            "native wrap complete via ${state.source} -> page ${state.targetPage}"
        )
    }

    private fun restoreNativeWrap(owner: Any, state: NativeWrapState) {
        if (
            state.pageScrolls.size == state.originalPageScrolls.size &&
            readField(owner, "mPageScrolls") === state.pageScrolls
        ) {
            state.originalPageScrolls.copyInto(state.pageScrolls)
        }

        state.movedPages.forEach { moved ->
            moved.view.offsetLeftAndRight(moved.originalLeft - moved.view.left)
        }

        writeField(owner, "mMinScroll", state.originalMinScroll)
        writeField(owner, "mMaxScroll", state.originalMaxScroll)

        val view = owner as? View
        val scrollY = view?.scrollY ?: 0
        invokeVoidTwoInts(owner, "scrollTo", state.realTargetScroll, scrollY)
        view?.invalidate()
    }

    private fun validatedLastPage(owner: Any, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return invokeIntOneArg(owner, "validateNewPage", pageCount - 1)
            ?: (pageCount - 1)
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
        writeField(owner, "mDiffScrollX", 0)
        writeField(owner, "mLastAmount", 0)
        writeField(owner, "mTempAmount", 0)
        writeField(owner, "mOverScrollAmount", 0)
        writeField(owner, "wasInOverscroll", false)
    }

    private fun invokeIntNoArg(owner: Any, name: String): Int? {
        val method = findMethod(owner, name) ?: return null
        return runCatching { (method.invoke(owner) as? Number)?.toInt() }.getOrNull()
    }

    private fun invokeIntOneArg(owner: Any, name: String, value: Int): Int? {
        val method = findMethod(owner, name, Int::class.javaPrimitiveType!!) ?: return null
        return runCatching { (method.invoke(owner, value) as? Number)?.toInt() }.getOrNull()
    }

    private fun invokeBooleanNoArg(owner: Any, name: String): Boolean? {
        val method = findMethod(owner, name) ?: return null
        return runCatching { method.invoke(owner) as? Boolean }.getOrNull()
    }

    private fun invokeBooleanOneInt(owner: Any, name: String, value: Int): Boolean? {
        val method = findMethod(owner, name, Int::class.javaPrimitiveType!!) ?: return null
        return runCatching { method.invoke(owner, value) as? Boolean }.getOrNull()
    }

    private fun invokeViewOneInt(owner: Any, name: String, value: Int): View? {
        val method = findMethod(owner, name, Int::class.javaPrimitiveType!!) ?: return null
        return runCatching { method.invoke(owner, value) as? View }.getOrNull()
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
