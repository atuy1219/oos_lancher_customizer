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

        // getUxIconDrawable on ColorOS 17 may return a full-color adaptive icon with
        // a monochrome layer copied from the app itself. It is NOT the ColorOS 15
        // built-in special Material glyph. Passing this layer through our rendering
        // pipeline also inflated a subset of icons (previous v1 used 1.5x scaling).
        // Do not replace the known-good icon until the dedicated morphicon loader
        // can be invoked with a verified built-in style configuration.
        return null
    }
}
