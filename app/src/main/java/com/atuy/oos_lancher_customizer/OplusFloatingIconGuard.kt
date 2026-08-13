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

/**
 * Keeps OplusFloatingIconView from publishing a freshly reconstructed full-color icon
 * before ThemedIcons has a chance to correct the final floating icon.
 *
 * OxygenOS' getOplusIconResult() can schedule IconLoadResult.onIconLoaded immediately
 * after writing IconLoadResult.drawable. The normal FloatingIconView#setIcon safety net
 * can therefore lose a race by one frame. This guard temporarily detaches that callback,
 * replaces the load result with the already-themed drawable currently shown by the
 * BubbleTextView, then dispatches the callback.
 */
class OplusFloatingIconGuard : XposedModule() {

    companion object {
        private const val TAG = "OOS16_ThemedIconGuard"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val OPLUS_FLOATING_ICON_VIEW_CLASS =
            "com.android.launcher3.views.OplusFloatingIconView"
    }

    @Volatile
    private var hooksInstalled = false

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
        val clazz = Class.forName(OPLUS_FLOATING_ICON_VIEW_CLASS, false, classLoader)

        hookAllNamedMethods(clazz, "getOplusIconResult", "oplus-result") { chain ->
            val args = chain.args.toTypedArray()
            interceptOplusIconResult(chain, args)
        }
        moduleLog("hooked OplusFloatingIconView#getOplusIconResult")

        // Keep the cached result coherent for the synchronous local-icon path as well.
        hookAllNamedMethods(clazz, "fetchAndFillLocalIcon", "oplus-local") { chain ->
            val args = chain.args.toTypedArray()
            val result = chain.proceed(args)
            afterFetchAndFillLocalIcon(chain.thisObject, args)
            result
        }
        moduleLog("hooked OplusFloatingIconView#fetchAndFillLocalIcon")
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
                .setId("$hookIdPrefix($signature)")
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

        if (loadResult == null || itemInfo == null || !shouldPatch(itemInfo)) {
            return chain.proceed(args)
        }

        // Oplus may execute this callback from inside getOplusIconResult() immediately
        // after publishing an unthemed drawable. Detach it so no frame can observe that
        // intermediate result.
        val pendingCallback = readField(loadResult, "onIconLoaded") as? Runnable
        if (pendingCallback != null) {
            writeField(loadResult, "onIconLoaded", null)
        }

        return try {
            val result = chain.proceed(args)
            val patched = patchLoadResult(loadResult, originalView)

            if (pendingCallback != null) {
                // Match Oplus' one-shot callback semantics, but only after the drawable
                // has been replaced with the already-themed BubbleTextView icon.
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
            // Preserve the callback if the original method itself fails before completion.
            if (pendingCallback != null && readField(loadResult, "onIconLoaded") == null) {
                runCatching { writeField(loadResult, "onIconLoaded", pendingCallback) }
            }
            throw error
        }
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

    private fun patchLoadResult(loadResult: Any, originalView: View?): Boolean {
        val view = originalView ?: return false
        val currentIcon = invokeNoArg(view, "getIcon") as? Drawable ?: return false

        val drawable = cloneDrawable(currentIcon, view.context)
        drawable.bounds = currentIcon.bounds
        writeField(loadResult, "drawable", drawable)

        // Some Oplus paths retain a separate BubbleTextView drawable snapshot.
        val btvDrawable = cloneDrawable(currentIcon, view.context)
        btvDrawable.bounds = currentIcon.bounds
        runCatching { writeField(loadResult, "btvDrawable", btvDrawable) }

        // Present on some launcher revisions; harmlessly ignored when absent.
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
