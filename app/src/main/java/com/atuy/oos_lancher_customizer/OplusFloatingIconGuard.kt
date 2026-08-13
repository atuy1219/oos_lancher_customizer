package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Prevents Oplus' floating-icon pipeline from publishing or rendering a reconstructed
 * full-color icon after ThemedIcons already themed the launcher BubbleTextView.
 *
 * There are two protections:
 * 1. Hold IconLoadResult.onIconLoaded until its drawable is replaced by the icon that is
 *    actually visible on the BubbleTextView.
 * 2. Associate that themed icon with the FloatingIconView's ClipIconView and replace the
 *    drawable again at the final ClipIconView#setIcon render sink. This closes the race
 *    where onIconLoaded is registered while getOplusIconResult() is still running.
 */
class OplusFloatingIconGuard : XposedModule() {

    companion object {
        private const val TAG = "OOS16_ThemedIconGuard"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val OPLUS_FLOATING_ICON_VIEW_CLASS =
            "com.android.launcher3.views.OplusFloatingIconView"
        private const val CLIP_ICON_VIEW_CLASS =
            "com.android.launcher3.views.ClipIconView"
        private const val OPLUS_CLIP_ICON_VIEW_CLASS =
            "com.android.launcher3.views.OplusClipIconView"
    }

    @Volatile
    private var hooksInstalled = false

    // FloatingIconView/ClipIconView are short-lived/reused launcher views. Weak keys avoid
    // retaining them, while every new animation refreshes or removes its override.
    private val clipIconOverrides =
        Collections.synchronizedMap(WeakHashMap<Any, Drawable>())

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE || hooksInstalled) return

        runCatching {
            installHooks(param.defaultClassLoader)
            hooksInstalled = true
        }.onFailure {
            moduleLog("critical: ${it.message}", it)
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        val floatingClass = Class.forName(OPLUS_FLOATING_ICON_VIEW_CLASS, false, classLoader)

        hookAllNamedMethods(floatingClass, "getOplusIconResult", "oplus-result") { chain ->
            val args = chain.args.toTypedArray()
            interceptOplusIconResult(chain, args)
        }
        moduleLog("hooked OplusFloatingIconView#getOplusIconResult")

        hookAllNamedMethods(floatingClass, "fetchAndFillLocalIcon", "oplus-local") { chain ->
            val args = chain.args.toTypedArray()
            beforeFetchAndFillLocalIcon(chain.thisObject, args)
            val result = chain.proceed(args)
            afterFetchAndFillLocalIcon(chain.thisObject, args)
            result
        }
        moduleLog("hooked OplusFloatingIconView#fetchAndFillLocalIcon")

        // OplusClipIconView overrides setIcon and calls into the base implementation on
        // this launcher build. Hook both so the final render input is covered even if the
        // implementation changes which level performs the actual drawing.
        listOf(CLIP_ICON_VIEW_CLASS, OPLUS_CLIP_ICON_VIEW_CLASS).forEach { className ->
            runCatching {
                val clipClass = Class.forName(className, false, classLoader)
                hookAllNamedMethods(clipClass, "setIcon", "clip-setIcon") { chain ->
                    val args = chain.args.toTypedArray()
                    beforeClipSetIcon(chain.thisObject, args)
                    chain.proceed(args)
                }
                moduleLog("hooked $className#setIcon")
            }.onFailure {
                moduleLog("$className hook skipped: ${it.message}")
            }
        }
    }

    private fun hookAllNamedMethods(
        clazz: Class<*>,
        methodName: String,
        hookIdPrefix: String,
        interceptor: (XposedInterface.Chain) -> Any?
    ) {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        check(methods.isNotEmpty()) { "${clazz.name}#$methodName not found" }

        methods.forEach { method ->
            method.isAccessible = true
            val signature = method.parameterTypes.joinToString(",") { it.name }
            hook(method)
                .setId("$hookIdPrefix-${clazz.simpleName}($signature)")
                .intercept { chain -> interceptor(chain) }
        }
    }

    private fun interceptOplusIconResult(
        chain: XposedInterface.Chain,
        args: Array<Any?>
    ): Any? {
        // Signature in the supplied OOS launcher:
        // (Launcher, View, ItemInfo, RectF, FloatingIconView.IconLoadResult, boolean)
        val originalView = args.getOrNull(1) as? View
        val itemInfo = args.getOrNull(2)
        val loadResult = args.getOrNull(4)

        rememberClipOverride(chain.thisObject, originalView, itemInfo)

        if (loadResult == null || itemInfo == null || !shouldPatch(itemInfo)) {
            return chain.proceed(args)
        }

        // If checkIconResult() has already registered its callback, prevent Oplus from
        // scheduling it with the intermediate reconstructed drawable.
        val pendingCallback = readField(loadResult, "onIconLoaded") as? Runnable
        if (pendingCallback != null) {
            writeField(loadResult, "onIconLoaded", null)
        }

        return try {
            val result = chain.proceed(args)
            val patched = patchLoadResult(loadResult, originalView)

            if (pendingCallback != null) {
                writeField(loadResult, "onIconLoaded", pendingCallback)
                dispatchOnMain(chain.thisObject, originalView, pendingCallback)
                writeField(loadResult, "onIconLoaded", null)
            }

            moduleLog(
                "oplus result ${if (patched) "patched" else "unchanged"} " +
                    "pkg=${resolvePackageName(itemInfo)} callback=${pendingCallback != null}"
            )
            result
        } catch (error: Throwable) {
            if (pendingCallback != null && readField(loadResult, "onIconLoaded") == null) {
                runCatching { writeField(loadResult, "onIconLoaded", pendingCallback) }
            }
            throw error
        }
    }

    private fun beforeFetchAndFillLocalIcon(owner: Any?, args: Array<Any?>) {
        val originalView = args.getOrNull(1) as? View
        val itemInfo = args.getOrNull(2)
        rememberClipOverride(owner, originalView, itemInfo)
    }

    private fun afterFetchAndFillLocalIcon(owner: Any?, args: Array<Any?>) {
        val originalView = args.getOrNull(1) as? View ?: return
        val itemInfo = args.getOrNull(2) ?: return
        if (!shouldPatch(itemInfo)) return

        val loadResult = readField(owner, "mIconLoadResult") ?: return
        if (patchLoadResult(loadResult, originalView)) {
            moduleLog("local result patched pkg=${resolvePackageName(itemInfo)}")
        }
    }

    private fun rememberClipOverride(owner: Any?, originalView: View?, itemInfo: Any?) {
        val clipView = readField(owner, "mClipIconView") ?: return

        if (itemInfo == null || !shouldPatch(itemInfo) || originalView == null) {
            clipIconOverrides.remove(clipView)
            return
        }

        val currentIcon = invokeNoArg(originalView, "getIcon") as? Drawable
        if (currentIcon == null) {
            clipIconOverrides.remove(clipView)
            return
        }

        val snapshot = cloneDrawable(currentIcon, originalView.context)
        snapshot.bounds = currentIcon.bounds
        clipIconOverrides[clipView] = snapshot
        moduleLog(
            "clip override armed pkg=${resolvePackageName(itemInfo)} " +
                "drawable=${currentIcon.javaClass.simpleName}"
        )
    }

    private fun beforeClipSetIcon(owner: Any?, args: Array<Any?>) {
        val clipView = owner ?: return
        val override = clipIconOverrides[clipView] ?: return
        val context = (owner as? View)?.context ?: return
        val originalArg = args.firstOrNull() as? Drawable

        val replacement = cloneDrawable(override, context)
        replacement.bounds = originalArg?.bounds ?: override.bounds
        args[0] = replacement

        moduleLog(
            "clip sink forced ${owner.javaClass.simpleName}: " +
                "${originalArg?.javaClass?.simpleName} -> ${replacement.javaClass.simpleName}"
        )
    }

    private fun patchLoadResult(loadResult: Any, originalView: View?): Boolean {
        val view = originalView ?: return false
        val currentIcon = invokeNoArg(view, "getIcon") as? Drawable ?: return false

        val drawable = cloneDrawable(currentIcon, view.context)
        drawable.bounds = currentIcon.bounds
        writeField(loadResult, "drawable", drawable)

        val btvDrawable = cloneDrawable(currentIcon, view.context)
        btvDrawable.bounds = currentIcon.bounds
        runCatching { writeField(loadResult, "btvDrawable", btvDrawable) }
        runCatching { writeField(loadResult, "isThemed", true) }
        return true
    }

    private fun cloneDrawable(drawable: Drawable, context: Context): Drawable {
        return runCatching {
            drawable.constantState?.newDrawable(context.resources)?.mutate()
        }.getOrNull() ?: drawable
    }

    private fun dispatchOnMain(owner: Any?, originalView: View?, callback: Runnable) {
        val context = originalView?.context ?: (owner as? View)?.context
        if (context == null) {
            callback.run()
            return
        }
        context.mainExecutor.execute(callback)
    }

    private fun shouldPatch(itemInfo: Any): Boolean {
        val pkg = resolvePackageName(itemInfo) ?: return false
        return pkg.isNotBlank() && pkg != TARGET_PACKAGE
    }

    private fun resolvePackageName(itemInfo: Any): String? {
        (readField(itemInfo, "componentName") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (invokeNoArg(itemInfo, "getTargetComponent") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (invokeNoArg(itemInfo, "getMTargetComponent") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (readField(itemInfo, "packageName") as? String)
            ?.takeIf { it.contains('.') }
            ?.let { return it }

        (invokeNoArg(itemInfo, "getTargetPackage") as? String)
            ?.takeIf { it.contains('.') }
            ?.let { return it }

        return null
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
        field.set(owner, value)
    }

    private fun findNoArgMethod(owner: Any, name: String): Method? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val method = current.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                return method
            }
            cls = current.superclass
        }
        return null
    }

    private fun invokeNoArg(owner: Any, name: String): Any? {
        return runCatching { findNoArgMethod(owner, name)?.invoke(owner) }.getOrNull()
    }

    private fun moduleLog(msg: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log(Log.INFO, TAG, msg)
        } else {
            log(Log.ERROR, TAG, msg, throwable)
        }
    }
}
