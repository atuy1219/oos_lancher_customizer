package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageItemInfo
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Load only ColorOS' *dedicated monochrome files*, not the standard adaptive
 * icon's monochrome layer. ColorOS 15 and ColorOS 16.6.20 both contain
 * UxIconLoaderHelper#getIconThemeDrawable; 16.6.20 adds the component-based API.
 *
 * The OEM method searches /my_product/media/theme/uxicons/.../monochrome
 * and its icon-pack fallbacks. If the old file was removed by an OS update,
 * this cannot recreate it; the caller preserves its original fallback path.
 *
 * No old OEM code, bitmap resources, or special icon packs are bundled.
 */
internal object OplusMaterialIconSource {
    private const val TAG = "OOS16_OemMaterial"
    private const val OEM_HELPER = "com.oplus.uxicon.ui.util.UxIconLoaderHelper"

    internal data class Artwork(val drawable: Drawable, val source: String)

    private data class CacheEntry(
        val constantState: Drawable.ConstantState?,
        val source: String
    )

    // Null state is a negative lookup. Only resolve filesystem-backed OEM icons
    // once per component/launcher process, instead of doing IO during every rebind.
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>?
            ): Boolean = size > 256
        }
    )

    internal fun isPreinstalledSystemApp(flags: Int): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun load(context: Context, component: ComponentName): Artwork? {
        val key = component.flattenToString()
        synchronized(cache) {
            if (cache.containsKey(key)) {
                return cache[key]?.let { entry ->
                    entry.constantState?.newDrawable(context.resources)?.mutate()
                        ?.let { Artwork(it, entry.source) }
                }
            }
        }

        val pm = context.packageManager
        val isSystem = runCatching {
            isPreinstalledSystemApp(pm.getApplicationInfo(component.packageName, 0).flags)
        }.getOrDefault(false)
        if (!isSystem) {
            synchronized(cache) { cache[key] = CacheEntry(null, "not-system") }
            return null
        }

        val helper = runCatching {
            Class.forName(OEM_HELPER, false, context.classLoader)
        }.getOrNull() ?: return null

        // ColorOS 16.6.20: this passes a real component name and falls back to
        // the "monochrome" filename in UxIconLoaderUtil. An empty suffix is
        // intentional; it means the default monochrome asset, not a color icon.
        var selectedSource = "component-monochrome"
        val componentDrawable = runCatching {
            val method = helper.getDeclaredMethod(
                "getIconThemeDrawableWithComponent",
                Context::class.java,
                ComponentName::class.java,
                String::class.java
            )
            if (!Modifier.isStatic(method.modifiers)) return@runCatching null
            method.isAccessible = true
            method.invoke(null, context, component, "") as? Drawable
        }.getOrNull()

        val chosen = componentDrawable ?: run {
            selectedSource = "activity-monochrome"
            // ColorOS 15: the dedicated file loader is also reachable through
            // ActivityInfo. It does not rely on the device's current icon style.
            runCatching {
                val activity = pm.getActivityInfo(component, 0)
                val method = helper.getDeclaredMethod(
                    "getIconThemeDrawable",
                    Context::class.java,
                    PackageItemInfo::class.java
                )
                if (!Modifier.isStatic(method.modifiers)) return@runCatching null
                method.isAccessible = true
                method.invoke(null, context, activity) as? Drawable
            }.getOrNull()
        }

        // Generic AdaptiveIconDrawable is NOT evidence of a dedicated OEM file.
        val verified = chosen?.takeUnless { it is AdaptiveIconDrawable }
        val state = verified?.constantState
        synchronized(cache) {
            cache[key] = CacheEntry(state, if (state != null) selectedSource else "not-found")
        }
        if (state != null) {
            Log.i(TAG, "dedicated OEM monochrome file: $key via $selectedSource")
            return Artwork(state.newDrawable(context.resources).mutate(), selectedSource)
        }
        return null
    }
}
