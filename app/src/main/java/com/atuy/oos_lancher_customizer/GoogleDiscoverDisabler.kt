package com.atuy.oos_lancher_customizer

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Disables the Google Discover / Google overlay page in Oplus Launcher without disabling
 * other minus-one providers such as Shelf.
 *
 * The supplied OplusLauncher build gates the Google page through
 * AppFeatureUtils.isSupportGoogleOverlay(), then checks it again through
 * OverlayUtils.selectedGoogleAssistantScreen(). Hook both layers so a runtime feature update
 * cannot re-enable Discover while the module is active.
 */
class GoogleDiscoverDisabler : XposedModule() {

    companion object {
        private const val TAG = "OOS16_DiscoverDisabler"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val APP_FEATURE_UTILS = "com.android.common.util.AppFeatureUtils"
        private const val OVERLAY_UTILS = "com.android.overlay.OverlayUtils"
    }

    @Volatile
    private var hooksInstalled = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE || hooksInstalled) return

        var installed = 0
        installed += hookBooleanGetter(
            param.defaultClassLoader,
            APP_FEATURE_UTILS,
            "isSupportGoogleOverlay"
        )
        installed += hookBooleanSetterFalse(
            param.defaultClassLoader,
            APP_FEATURE_UTILS,
            "setSupportGoogleOverlay"
        )
        installed += hookBooleanGetter(
            param.defaultClassLoader,
            OVERLAY_UTILS,
            "selectedGoogleAssistantScreen"
        )

        check(installed > 0) { "No Google Discover hooks could be installed" }
        hooksInstalled = true
        moduleLog("Google Discover disabled; installed hooks=$installed")
    }

    private fun hookBooleanGetter(
        classLoader: ClassLoader,
        className: String,
        methodName: String
    ): Int {
        return runCatching {
            val clazz = Class.forName(className, false, classLoader)
            val methods = clazz.declaredMethods.filter {
                it.name == methodName &&
                    it.parameterCount == 0 &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            check(methods.isNotEmpty()) { "$className#$methodName not found" }

            methods.forEach { method ->
                method.isAccessible = true
                hook(method)
                    .setId("discover-off-${clazz.simpleName}-$methodName")
                    .intercept { false }
            }

            moduleLog("hooked $className#$methodName -> false")
            methods.size
        }.onFailure {
            moduleLog("$className#$methodName hook skipped: ${it.message}")
        }.getOrDefault(0)
    }

    private fun hookBooleanSetterFalse(
        classLoader: ClassLoader,
        className: String,
        methodName: String
    ): Int {
        return runCatching {
            val clazz = Class.forName(className, false, classLoader)
            val methods = clazz.declaredMethods.filter {
                it.name == methodName &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    it.returnType == Void.TYPE
            }
            check(methods.isNotEmpty()) { "$className#$methodName(boolean) not found" }

            methods.forEach { method ->
                method.isAccessible = true
                hook(method)
                    .setId("discover-off-${clazz.simpleName}-$methodName")
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        args[0] = false
                        chain.proceed(args)
                    }
            }

            moduleLog("hooked $className#$methodName(true) -> false")
            methods.size
        }.onFailure {
            moduleLog("$className#$methodName hook skipped: ${it.message}")
        }.getOrDefault(0)
    }

    private fun moduleLog(msg: String) {
        log(Log.INFO, TAG, msg)
    }
}
