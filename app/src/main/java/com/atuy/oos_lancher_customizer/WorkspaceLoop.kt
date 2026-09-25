package com.atuy.oos_lancher_customizer

import android.content.SharedPreferences
import android.util.Log
import android.view.MotionEvent
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Optional wrap-around paging for the home-screen Workspace.
 *
 * PagedView is also used by folders and other launcher surfaces, therefore every hook is
 * restricted to OplusWorkspace instances.
 */
class WorkspaceLoop : XposedModule() {

    companion object {
        private const val TAG = "OOS16_WorkspaceLoop"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val PAGED_VIEW_CLASS = "com.android.launcher3.PagedView"
        private const val WORKSPACE_CLASS = "com.android.launcher3.OplusWorkspace"
    }

    private data class TouchStart(val x: Float, val y: Float, val page: Int)

    @Volatile
    private var hooksInstalled = false

    @Volatile
    private var loopEnabled = false

    private var remotePrefs: SharedPreferences? = null
    private val touchStarts = Collections.synchronizedMap(WeakHashMap<Any, TouchStart>())

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

        val onTouchEvent = pagedViewClass.getDeclaredMethod(
            "onTouchEvent",
            MotionEvent::class.java
        ).apply { isAccessible = true }

        hook(onTouchEvent)
            .setId("workspace-loop-touch")
            .setPriority(XposedInterface.PRIORITY_HIGHEST)
            .intercept { chain ->
                val owner = chain.thisObject
                val event = chain.args[0] as? MotionEvent
                if (owner == null || event == null || !workspaceClass.isInstance(owner)) {
                    return@intercept chain.proceed()
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
                        val wrapTarget = start?.let { calculateTouchWrapTarget(owner, event, it) }
                        val result = chain.proceed()
                        if (wrapTarget != null && stillAtEdge(owner, start, wrapTarget)) {
                            wrapTo(owner, wrapTarget, "touch")
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
        moduleLog("hooked OplusWorkspace touch and edge navigation")
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
        val threshold = 48f * density
        if (abs(dx) < threshold || abs(dx) <= abs(dy)) return null

        val flipped = invokeBooleanNoArg(owner, "isPageOrderFlipped") ?: false
        val outwardFromFirst = if (flipped) dx < 0f else dx > 0f
        val outwardFromLast = if (flipped) dx > 0f else dx < 0f

        return when {
            start.page <= 0 && outwardFromFirst -> last
            start.page >= last && outwardFromLast -> 0
            else -> null
        }
    }

    private fun stillAtEdge(owner: Any, start: TouchStart, target: Int): Boolean {
        val current = invokeIntNoArg(owner, "getCurrentPage") ?: return false
        val pageCount = invokeIntNoArg(owner, "getPageCount") ?: return false
        val last = validatedLastPage(owner, pageCount)
        return if (target == 0) current >= last && start.page >= last else current <= 0 && start.page <= 0
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

                val pageCount = invokeIntNoArg(owner, "getPageCount")
                    ?: return@intercept chain.proceed()
                if (pageCount <= 1) return@intercept chain.proceed()

                val current = invokeIntNoArg(owner, "getNextPage")
                    ?: invokeIntNoArg(owner, "getCurrentPage")
                    ?: return@intercept chain.proceed()
                val last = validatedLastPage(owner, pageCount)
                val atEdge = if (wrapToLast) current <= 0 else current >= last
                if (!atEdge) return@intercept chain.proceed()

                val target = if (wrapToLast) last else 0
                if (wrapTo(owner, target, methodName)) true else chain.proceed()
            }
    }

    private fun validatedLastPage(owner: Any, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        return invokeIntOneArg(owner, "validateNewPage", pageCount - 1) ?: (pageCount - 1)
    }

    private fun wrapTo(owner: Any, target: Int, source: String): Boolean {
        invokeVoidNoArg(owner, "abortScrollerAnimation")
        val method = findMethod(owner, "setCurrentPage", Int::class.javaPrimitiveType!!) ?: return false
        return runCatching {
            method.invoke(owner, target)
            moduleLog("wrapped via $source -> page $target")
            true
        }.getOrElse {
            moduleLog("wrap failed via $source: ${it.message}")
            false
        }
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

    private fun invokeVoidNoArg(owner: Any, name: String) {
        val method = findMethod(owner, name) ?: return
        runCatching { method.invoke(owner) }
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
