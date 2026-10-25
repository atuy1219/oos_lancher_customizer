package com.atuy.oos_lancher_customizer

import kotlin.math.roundToInt

/**
 * Reproduces the fallback path decoded from the supplied Pixel Launcher 17-907
 * (MonoIconThemeController#createThemedBitmap, BaseIconFactory#wrapToAdaptiveIcon).
 *
 * The Pixel implementation first normalizes an ALPHA_8 grayscale buffer to
 * 0..255, then applies a piecewise quadratic curve. Unlike the previous
 * OOS module, it does not invert the pixels based on edge luminance.
 *
 * The legacy source icon is first wrapped in a square InsetDrawable with
 * BaseIconFactory.LEGACY_ICON_SCALE, then in an AdaptiveIconDrawable.
 */
internal object Pixel17MonoMath {
    /**
     * Extra output zoom applied only to the generated monochrome fallback.
     * The Pixel algorithm is unchanged; native monochrome, OOS15 and Pixel
     * bundled artwork continue to use their existing scale.
     */
    internal const val GENERATED_OUTPUT_SCALE = 0.60f
    // IEEE754 float value 0x3f109764 from Pixel 17-907 BaseIconFactory.<clinit>.
    private const val LEGACY_BASE_SCALE = 0.564810037612915f

    internal fun legacyWrappedScale(extraInsetFraction: Float): Float =
        LEGACY_BASE_SCALE / (1f + 2f * extraInsetFraction)

    internal data class Insets(val left: Float, val top: Float)

    internal fun legacyInsets(width: Int, height: Int, scale: Float): Insets {
        val w = if (width > 0 && height > 0) {
            if (width < height) scale * width.toFloat() / height else scale
        } else scale
        val h = if (width > 0 && height > 0) {
            if (height < width) scale * height.toFloat() / width else scale
        } else scale
        return Insets((1f - w) / 2f, (1f - h) / 2f)
    }

    internal fun normalizeAndSmooth(pixels: ByteArray) {
        var min = 255
        var max = 0
        for (raw in pixels) {
            val v = raw.toInt() and 255
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min >= max) return
        val span = (max - min).toFloat()
        for (i in pixels.indices) {
            val p = pixels[i].toInt() and 255
            pixels[i] = (((p - min) * 255f / span).roundToInt()).toByte()
        }
        for (i in pixels.indices) {
            val p = pixels[i].toInt() and 255
            val adjusted = if (p > 128) {
                255 - ((255 - p) * (1.0 - (p - 128) / 128.0)).toInt()
            } else {
                (p * (1.0 - (128 - p) / 128.0)).toInt()
            }
            pixels[i] = adjusted.coerceIn(0, 255).toByte()
        }
    }
}
