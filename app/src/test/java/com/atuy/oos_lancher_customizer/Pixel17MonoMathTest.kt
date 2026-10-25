package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pixel17MonoMathTest {
    @Test
    fun pixel17ContrastCurveDoesNotReverseBlackAndWhiteOrAverageEdges() {
        val pixels = byteArrayOf(0, 64, 128.toByte(), 192.toByte(), 255.toByte())
        Pixel17MonoMath.normalizeAndSmooth(pixels)
        assertArrayEquals(
            byteArrayOf(0, 32, 128.toByte(), 224.toByte(), 255.toByte()),
            pixels
        )
    }

    @Test
    fun pixel17ContrastStretchesGrayRangeBeforeSmoothing() {
        val pixels = byteArrayOf(64, 96, 128.toByte(), 160.toByte(), 192.toByte())
        Pixel17MonoMath.normalizeAndSmooth(pixels)
        assertArrayEquals(
            byteArrayOf(0, 32, 128.toByte(), 223.toByte(), 255.toByte()),
            pixels
        )
    }

    @Test
    fun pixel17ConstantGrayStaysUnchangedWithoutDivisionByZero() {
        val pixels = byteArrayOf(77, 77, 77)
        Pixel17MonoMath.normalizeAndSmooth(pixels)
        assertArrayEquals(byteArrayOf(77, 77, 77), pixels)
    }

    @Test
    fun pixel17NonAdaptiveWrapUsesDecompiledScaleAndAspectRatio() {
        val scale = Pixel17MonoMath.legacyWrappedScale(0.25f)
        assertEquals(0.37654f, scale, 0.00002f)

        val square = Pixel17MonoMath.legacyInsets(192, 192, scale)
        assertEquals((1f - scale) / 2, square.left, 0.00001f)
        assertEquals(square.left, square.top, 0.00001f)

        val portrait = Pixel17MonoMath.legacyInsets(100, 200, scale)
        assertEquals((1f - scale * 0.5f) / 2f, portrait.left, 0.00001f)
        assertEquals((1f - scale) / 2f, portrait.top, 0.00001f)

        val landscape = Pixel17MonoMath.legacyInsets(200, 100, scale)
        assertEquals((1f - scale) / 2f, landscape.left, 0.00001f)
        assertEquals((1f - scale * 0.5f) / 2f, landscape.top, 0.00001f)
    }

    @Test
    fun originalOos15AndPixelExplicitScalesAreUnaffected() {
        assertEquals(1.5f, Oos15MonochromeAssets.GLYPH_SCALE, 0f)
        assertEquals(0.6f, PixelLauncherThemedAssets.GLYPH_SCALE, 0.000001f)
    }
}
