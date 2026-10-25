package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class Pixel17IconNormalizerTest {
    @Test
    fun opaqueSquareIsShrunkToLaunchersSquareAreaLimit() {
        val scale = Pixel17IconNormalizer.areaScale(64f * 64f, 64f * 64f, 64f * 64f)
        assertEquals(sqrt(375f / 576f), scale, 0.00001f)
    }

    @Test
    fun circularShapeIsShrunkUsingConvexHullAndAreaThreshold() {
        val side = 64
        val pixels = ByteArray(side * side)
        val center = (side - 1) / 2.0
        for (y in 0 until side) for (x in 0 until side) {
            val dx = x - center
            val dy = y - center
            if (dx * dx + dy * dy <= 31 * 31) pixels[y * side + x] = 255.toByte()
        }
        val scale = Pixel17IconNormalizer.measureAlpha(pixels, side, side, side)
        assertTrue("normalized circle: $scale", scale in 0.85f..1f)
    }

    @Test
    fun smallLegacyGlyphKeepsItsOriginalSourceScale() {
        val side = 64
        val pixels = ByteArray(side * side)
        for (y in 20 until 44) for (x in 20 until 44) {
            pixels[y * side + x] = 255.toByte()
        }
        assertEquals(1f, Pixel17IconNormalizer.measureAlpha(pixels, side, side, side), 0f)
    }

    @Test
    fun nonTransparentThresholdExcludesFaintPixels() {
        val side = 64
        val pixels = ByteArray(side * side) { 40 }
        assertEquals(1f, Pixel17IconNormalizer.measureAlpha(pixels, side, side, side), 0f)
    }

    @Test
    fun invalidAndEmptyIconAlphasSafelyReturnUnscaled() {
        assertEquals(1f, Pixel17IconNormalizer.areaScale(0f, 0f, 192f * 192f), 0f)
        assertEquals(1f, Pixel17IconNormalizer.measureAlpha(byteArrayOf(), 1, 64, 64), 0f)
        assertEquals(1f, Pixel17IconNormalizer.measureAlpha(ByteArray(64 * 64), 64, 64, 64), 0f)
    }

    @Test
    fun originalOos15AndPixelAssetsAreNotAffected() {
        assertEquals(1.50f, Oos15MonochromeAssets.glyphScale("com.oneplus.gallery"), 0f)
        assertEquals(0.60f, PixelLauncherThemedAssets.GLYPH_SCALE, 0.00001f)
    }
}
