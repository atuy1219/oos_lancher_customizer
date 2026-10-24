package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageItemInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.lang.reflect.Modifier

/**
 * Experimental bridge for ColorOS 15's dedicated system-app monochrome artwork.
 * Try existing OEM loader interfaces, never package old launcher classes/assets.
 */
internal object OplusMaterialIconSource {
    private const val TAG = "OOS16_OemMaterial"

    data class Artwork(val drawable: Drawable, val adaptiveLayer: Boolean, val source: String)

    internal fun isPreinstalledSystemApp(flags: Int): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun load(context: Context, component: ComponentName, original: Drawable): Artwork? {
        val pm = context.packageManager
        val flags = runCatching {
            pm.getApplicationInfo(component.packageName, 0).flags
        }.getOrNull() ?: return null
        if (!isPreinstalledSystemApp(flags)) return null

        // The old method returns the designated monochrome graphic itself.
        val legacy = runCatching {
            val loader = Class.forName(
                "com.oplus.uxicon.ui.util.UxIconLoaderHelper",
                false,
                context.classLoader
            )
            val method = loader.getDeclaredMethod(
                "getIconThemeDrawable",
                Context::class.java,
                PackageItemInfo::class.java
            )
            if (!Modifier.isStatic(method.modifiers)) return@runCatching null
            method.isAccessible = true
            val activity = pm.getActivityInfo(component, 0)
            method.invoke(null, context, activity) as? Drawable
        }.getOrNull()
        if (legacy != null) {
            Log.i(TAG, "legacy monochrome artwork: " + component.flattenToShortString())
            return Artwork(legacy, adaptiveLayer = false, source = "coloros15")
        }

        // OplusUxIconManager is called by current ColorOS' convertToThemeStyle().
        // Only a dedicated monochrome layer qualifies; a color composite does not.
        val native = runCatching {
            val manager = Class.forName(
                "com.oplus.icon.OplusUxIconManager",
                false,
                context.classLoader
            )
            val method = manager.declaredMethods.firstOrNull {
                it.name == "getUxIconDrawable" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0].isAssignableFrom(pm.javaClass) &&
                    it.parameterTypes[1] == String::class.java &&
                    Drawable::class.java.isAssignableFrom(it.parameterTypes[2]) &&
                    it.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                    Modifier.isStatic(it.modifiers)
            } ?: return@runCatching null
            method.isAccessible = true
            method.invoke(null, pm, component.packageName, original, false) as? Drawable
        }.getOrNull() ?: return null

        val mono = if (native is AdaptiveIconDrawable) {
            native.monochrome
        } else {
            runCatching {
                val method = native.javaClass.methods.firstOrNull {
                    it.name == "getMonochrome" && it.parameterCount == 0 &&
                        Drawable::class.java.isAssignableFrom(it.returnType)
                }
                method?.invoke(native) as? Drawable
            }.getOrNull()
        } ?: return null

        Log.i(TAG, "framework monochrome artwork: " + component.flattenToShortString())
        return Artwork(mono, adaptiveLayer = true, source = "oplus-framework")
    }
}
