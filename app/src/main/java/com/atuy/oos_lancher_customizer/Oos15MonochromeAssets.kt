package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Bundled OEM glyphs extracted byte-for-byte from OxygenOS 15's my_stock uxicons.
 * Package-name matching is exact. Third-party apps absent from the bundle fall
 * back to the existing ColorOS 16 icon renderer without changing its cache key.
 */
internal object Oos15MonochromeAssets {
    private const val TAG = "OOS15_Monochrome"
    private const val MODULE = "com.atuy.oos_lancher_customizer"
    private const val BUNDLE = "oos15_monochrome.zip"
    private const val MAIN_GLYPH = "monochrome.png"
    private const val DIALER_GLYPH = "dialer_monochrome.png"

    /**
     * Enlarge the original OOS15 glyph inside the unchanged 192px theme circle.
     * Most OEM PNGs leave generous transparent space around their content.
     * The IR remote artwork approaches the bottom edge of its PNG, so use
     * a smaller zoom for that one package to keep its shape intact.
     */
    internal fun glyphScale(packageName: String): Float =
        if (packageName == "com.oplus.consumerIRApp") 1.08f else 1.12f

    @Volatile private var bytesByPackage: Map<String, ByteArray>? = null
    private val decoded = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount + 1023) / 1024
    }

    internal fun isDialerActivityName(cls: String?): Boolean {
        if (cls == null) return false
        return cls.contains("dialer", ignoreCase = true) ||
            cls.contains("dialtacts", ignoreCase = true) ||
            cls.contains("phone", ignoreCase = true)
    }

    internal fun isDialerActivity(component: ComponentName?): Boolean =
        isDialerActivityName(component?.className)

    private fun assetKey(pkg: String, file: String) = "$pkg/$file"

    fun hasArtwork(context: Context, pkg: String): Boolean =
        getAssets(context).containsKey(assetKey(pkg, MAIN_GLYPH))

    fun load(context: Context, pkg: String, component: ComponentName?): Bitmap? {
        val assets = getAssets(context)
        val defaultKey = assetKey(pkg, MAIN_GLYPH)
        if (!assets.containsKey(defaultKey)) return null
        val dialerKey = assetKey(pkg, DIALER_GLYPH)
        val selectedKey = if (pkg == "com.android.contacts" &&
            isDialerActivity(component) && assets.containsKey(dialerKey)) {
            dialerKey
        } else defaultKey

        decoded.get(selectedKey)?.let { return it }
        val source = assets[selectedKey] ?: return null
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size)
        if (bitmap == null) {
            Log.w(TAG, "Invalid PNG: $selectedKey")
            return null
        }
        decoded.put(selectedKey, bitmap)
        Log.i(TAG, "Using original OOS15 monochrome: $selectedKey")
        return bitmap
    }

    @Synchronized
    private fun getAssets(context: Context): Map<String, ByteArray> {
        bytesByPackage?.let { return it }
        val result = HashMap<String, Pair<Int, ByteArray>>()
        try {
            val moduleContext = context.createPackageContext(
                MODULE, Context.CONTEXT_IGNORE_SECURITY
            )
            moduleContext.assets.open(BUNDLE).use { stream ->
                readBundle(stream, result)
            }
        } catch (t: Exception) {
            Log.e(TAG, "Cannot access bundled OOS15 PNGs; using original renderer", t)
        }
        val assets = result.mapValues { it.value.second }
        bytesByPackage = assets
        Log.i(TAG, "Loaded OOS15 monochrome bundle: ${assets.keys.count { it.endsWith("/monochrome.png") }} packages")
        return assets
    }

    private fun readBundle(
        input: InputStream,
        result: MutableMap<String, Pair<Int, ByteArray>>
    ) {
        // Keep highest density file per package. PNG canvas already has the
        // OEM padding; resize the WHOLE bitmap, never crop alpha bounds.
        val priority = mapOf("xhdpi" to 1, "xxhdpi" to 2, "xxxhdpi" to 3)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val segments = entry.name.split('/')
                    if (segments.size == 3 &&
                        segments[1].contains('.') &&
                        (segments[2] == MAIN_GLYPH || segments[2] == DIALER_GLYPH)) {
                        val quality = priority[segments[0]] ?: 0
                        val key = assetKey(segments[1], segments[2])
                        if (quality > 0 && quality > (result[key]?.first ?: 0)) {
                            result[key] = quality to zip.readBytes()
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
