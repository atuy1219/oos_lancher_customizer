package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.PathParser
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * 11 supplementary theme glyphs from the user-supplied Pixel Launcher
 * 17-907 APK: res/xml/grayscale_icon_map.xml and associated vector drawables.
 * They are fallback graphics, never replacements for the OOS15 OEM assets
 * or a monochrome layer provided by the application's AdaptiveIconDrawable.
 *
 * Stored as compressed JSON path-data to preserve all original vector paths
 * and avoid dependency on a Pixel Launcher APK or Android 17 resources.
 * Every original vector uses a 36x36 viewport and monochrome fill.
 */
internal object PixelLauncherThemedAssets {
    private const val TAG = "Pixel_ThemedIcons"
    private const val SIZE = 192

    /**
     * Pixel and Oplus IconProvider theme fallback use InsetDrawable(d, .2f)
     * before attaching the vector as AdaptiveIconDrawable monochrome.
     * The additional adaptive foreground inset is compensated when drawn
     * by AdaptiveIconDrawable, leaving a net 60% vector canvas.
     *
     * This constant affects ONLY Pixel-exclusive vectors. The original OOS15
     * foreground scale remains 1.5f.
     */
    internal const val GLYPH_INSET_FRACTION = 0.20f
    internal const val GLYPH_SCALE = 1f - 2f * GLYPH_INSET_FRACTION

    private val icons: Map<String, String> = mapOf(
        "com.google.android.apps.classroom" to "classroom",
        "com.google.android.apps.translate" to "g_translate",
        "com.google.android.apps.recorder" to "recorder",
        "com.android.settings" to "settings",
        "com.google.android.apps.nbu.paisa.user" to "gpay",
        "com.google.android.ims" to "play_services",
        "com.google.android.apps.fitness" to "gfit_health",
        "com.google.android.apps.podcasts" to "podcasts",
        "com.google.android.apps.tips" to "pixeltips",
        "com.google.android.wearable.app" to "wear_os",
        "com.nest.android" to "nest"
    )

    fun hasArtwork(packageName: String): Boolean = icons.containsKey(packageName)

    // Compressed, lossless JSON: drawable name -> [[pathData, evenOdd], ...].
    private const val VECTOR_DATA = "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"

    @Volatile private var paths: JSONObject? = null
    private val bitmapCache = object : LruCache<String, Bitmap>(3 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount + 1023) / 1024
    }

    @Synchronized
    private fun vectors(): JSONObject? {
        paths?.let { return it }
        return runCatching {
            val raw = Base64.decode(VECTOR_DATA, Base64.DEFAULT)
            val decoded = InflaterInputStream(ByteArrayInputStream(raw))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(decoded).also { paths = it }
        }.onFailure { Log.e(TAG, "Unable to decode Pixel Launcher vector data", it) }
            .getOrNull()
    }

    @Synchronized
    fun load(packageName: String): Bitmap? {
        val glyph = icons[packageName] ?: return null
        bitmapCache.get(glyph)?.let { return it }
        return runCatching {
            val segments = vectors()?.getJSONArray(glyph) ?: return null
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(SIZE / 36f, SIZE / 36f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            for (i in 0 until segments.length()) {
                val part = segments.getJSONArray(i)
                val path = PathParser.createPathFromPathData(part.getString(0))
                    ?: error("Invalid original Pixel Launcher vector segment $glyph#$i")
                if (part.getBoolean(1)) path.fillType = Path.FillType.EVEN_ODD
                canvas.drawPath(path, paint)
            }
            bitmapCache.put(glyph, bitmap)
            Log.i(TAG, "Using Pixel Launcher original vector for $packageName")
            bitmap
        }.onFailure { Log.e(TAG, "Failed to render $packageName", it) }.getOrNull()
    }
}
