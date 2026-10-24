package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.util.Collections
import java.util.LinkedHashMap

/**
 * ColorOS 15 Material-style glyph loader.
 *
 * getIconThemeDrawableWithComponent() returns generic "monochrome" artwork,
 * not the Material icon-pack glyph. Use the same material-style prefix and
 * UxIconLoaderUtil#findAppDrawable as the ColorOS 16.6.20 factory-theme loader.
 *
 * No artwork is synthesized or bundled. Missing files leave icons unchanged.
 */
internal object OplusMaterialIconSource {
    private const val TAG = "OOS16_OemMaterial"
    private const val RESOLVER = "com.oplus.uxicon.ui.util.UxIconConfigResolver"
    private const val LOADER = "com.oplus.uxicon.ui.util.UxIconLoaderUtil"

    // System packages from Google are explicitly NOT OEM icon targets.
    // Expand only when more ColorOS 15 OEM designs are independently confirmed.
    private val OEM_MATERIAL_PACKAGES = setOf(
        "com.oneplus.gallery",
        "com.oneplus.filemanager",
        "com.oplus.camera"
    )

    internal data class Artwork(val drawable: Drawable, val source: String)
    private data class CacheEntry(val state: Drawable.ConstantState?, val source: String)

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>?
            ): Boolean = size > 64
        }
    )

    internal fun isEligiblePackage(packageName: String): Boolean =
        packageName in OEM_MATERIAL_PACKAGES

    fun load(context: Context, component: ComponentName): Artwork? {
        val pkg = component.packageName
        if (!isEligiblePackage(pkg)) return null

        // The Material file is keyed by package, not an arbitrary activity.
        synchronized(cache) {
            if (cache.containsKey(pkg)) {
                val hit = cache[pkg] ?: return null
                return hit.state?.newDrawable(context.resources)?.mutate()
                    ?.let { Artwork(it, hit.source) }
            }
        }

        val art = runCatching { loadMaterialAsset(context, pkg) }
            .onFailure { Log.w(TAG, "Material file lookup failed for $pkg", it) }
            .getOrNull()
        val saved = art?.drawable?.constantState
        synchronized(cache) {
            cache[pkg] = CacheEntry(saved, art?.source ?: "not-present")
        }

        if (art == null) {
            Log.i(TAG, "Material file not present: $pkg (using existing theme icon)")
            return null
        }
        Log.i(TAG, "Material file selected: $pkg source=" + art.source)
        return art
    }

    private fun loadMaterialAsset(context: Context, pkg: String): Artwork? {
        val loader = context.classLoader
        val resolverClass = Class.forName(RESOLVER, false, loader)
        val getInstance = resolverClass.getDeclaredMethod("getInstance", Context::class.java)
        getInstance.isAccessible = true
        val resolver = getInstance.invoke(null, context) ?: return null

        val getMaterialPosition = resolverClass.getDeclaredMethod("getMaterialThemeConfigPos")
        getMaterialPosition.isAccessible = true
        val pos = (getMaterialPosition.invoke(resolver) as? Number)?.toInt() ?: return null

        val getPrefix = resolverClass.getDeclaredMethod(
            "getCommonStylePrefixInPosition", Int::class.javaPrimitiveType
        )
        getPrefix.isAccessible = true
        val prefix = getPrefix.invoke(resolver, pos) as? String
        if (prefix.isNullOrBlank()) return null

        val utilClass = Class.forName(LOADER, false, loader)
        val getUtil = utilClass.getDeclaredMethod("getInstance")
        getUtil.isAccessible = true
        val util = getUtil.invoke(null) ?: return null

        // Six arguments after receiver in the 16.6.20 DEX.
        // z2 = true selects factory resources; z3 selects common vs legacy path.
        val find = utilClass.getDeclaredMethod(
            "findAppDrawable",
            String::class.java, String::class.java,
            android.content.res.Resources::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        find.isAccessible = true
        fun tryFactoryPath(modern: Boolean): Drawable? =
            find.invoke(util, pkg, prefix, context.resources, false, true, modern) as? Drawable

        val drawable = tryFactoryPath(true) ?: tryFactoryPath(false) ?: return null
        if (drawable is AdaptiveIconDrawable) return null
        return Artwork(drawable, "material-prefix=$prefix")
    }
}
