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
    fun regularSyntheticFallbackIsSixtyPercentOfPreviousSize() {
        assertEquals(0.60f, Pixel17MonoMath.GENERATED_OUTPUT_SCALE, 0f)
        assertEquals(173, Pixel17MonoMath.outputSize(288, shortcut = false, synthetic = true))
        assertEquals(115, Pixel17MonoMath.outputSize(192, shortcut = false, synthetic = true))
    }

    @Test
    fun shortcutIsOnePointFiveTimesPriorSizeRegardlessOfUnderlyingMonochromeSource() {
        assertEquals(1.50f, Pixel17MonoMath.SHORTCUT_OUTPUT_SCALE, 0f)
        // The old shortcut cap was 192px. Apply the extra 1.5x AFTER the cap.
        assertEquals(288, Pixel17MonoMath.outputSize(192, shortcut = true, synthetic = true))
        assertEquals(288, Pixel17MonoMath.outputSize(192, shortcut = true, synthetic = false))
        // Non-shortcut native monotone glyphs must be unaffected.
        assertEquals(192, Pixel17MonoMath.outputSize(192, shortcut = false, synthetic = false))
        assertEquals(288, Pixel17MonoMath.outputSize(288, shortcut = false, synthetic = false))
    }

    @Test
    fun originalOos15AndPixelExplicitScalesAreUnaffected() {
        assertEquals(1.5f, Oos15MonochromeAssets.glyphScale("com.oneplus.gallery"), 0f)
        assertEquals(0.6f, PixelLauncherThemedAssets.GLYPH_SCALE, 0.000001f)
    }
}
