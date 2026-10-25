package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Pixel17MonochromeRendererTest {
    private val resources get() = RuntimeEnvironment.getApplication().resources
    private val side = 192

    private class Glyph(
        private val width: Int = 108,
        private val height: Int = 108,
        private val fraction: Float = 1f,
        private val color: Int = Color.BLACK
    ) : Drawable() {
        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = height
        override fun draw(canvas: Canvas) {
            val dx = bounds.width() * (1f - fraction) / 2f
            val dy = bounds.height() * (1f - fraction) / 2f
            canvas.drawRect(bounds.left + dx, bounds.top + dy,
                bounds.right - dx, bounds.bottom - dy, Paint().apply { this.color = this@Glyph.color })
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Drawable API")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun visibleBounds(bitmap: Bitmap, foregroundWhite: Boolean): Rect {
        val rect = Rect(side, side, 0, 0)
        for (y in 0 until side) for (x in 0 until side) {
            val a = Color.alpha(bitmap.getPixel(x, y))
            if (if (foregroundWhite) a > 200 else a < 55) {
                rect.left = minOf(rect.left, x)
                rect.top = minOf(rect.top, y)
                rect.right = maxOf(rect.right, x + 1)
                rect.bottom = maxOf(rect.bottom, y + 1)
            }
        }
        return rect
    }

    private fun record(name: String, alpha: Bitmap) {
        val preview = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.rgb(50, 50, 50))
        canvas.drawBitmap(alpha, 0f, 0f, Paint().apply { color = Color.LTGRAY })
        val target = File("build/reports/pixel17/$name.png")
        target.parentFile.mkdirs()
        target.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun adaptiveGeneratedForegroundKeepsPixelViewportInsteadOfSixtyPercentShrink() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.BLACK),
            Glyph(fraction = 0.6f, color = Color.WHITE))
        val alpha = Pixel17MonochromeRenderer.render(icon, side, resources)
        val bounds = visibleBounds(alpha, true)
        // Pixel: raw foreground extent .60, negative inset expands by 1.50.
        assertEquals(0.90f, bounds.width() / side.toFloat(), 0.015f)
        assertEquals(0, Color.alpha(alpha.getPixel(0, 0)))
        record("adaptive-generated", alpha)
    }

    @Test
    fun legacyUsesPixelFixedWrapperEvenWhenItsOpaqueOutlineMatchesALauncherMask() {
        val alpha = Pixel17MonochromeRenderer.render(Glyph(), side, resources)
        val bounds = visibleBounds(alpha, false)
        // .564810/(1+2*.25), followed by the controller's negative .25 inset.
        // No mask-match branch, convex-hull normalization, or 0.60x adjustment.
        assertEquals(0.564810f, bounds.width() / side.toFloat(), 0.015f)
        assertEquals(0.564810f, bounds.height() / side.toFloat(), 0.015f)
        assertEquals(255, Color.alpha(alpha.getPixel(0, 0)))
        record("legacy-square", alpha)
    }

    @Test
    fun legacyPortraitPreservesAspectRatioThroughPixelWrapper() {
        val alpha = Pixel17MonochromeRenderer.render(Glyph(100, 200), side, resources)
        val bounds = visibleBounds(alpha, false)
        assertEquals(0.282405f, bounds.width() / side.toFloat(), 0.015f)
        assertEquals(0.564810f, bounds.height() / side.toFloat(), 0.015f)
        record("legacy-portrait", alpha)
    }

    @Test
    fun nativeMonochromeBypassesGrayscaleGenerationAndRestoresSourceBounds() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), Glyph(color = Color.BLUE),
            Glyph(fraction = 0.4f, color = Color.WHITE))
        val originalBounds = Rect(5, 10, 105, 110)
        icon.bounds = originalBounds
        val alpha = Pixel17MonochromeRenderer.render(icon, side, resources)
        val bounds = visibleBounds(alpha, true)
        assertEquals(0.60f, bounds.width() / side.toFloat(), 0.015f)
        assertEquals(originalBounds, icon.bounds)
        assertTrue(Color.alpha(alpha.getPixel(side / 2, side / 2)) > 250)
        record("native-monochrome", alpha)
    }
    @Test
    fun brightGeneratedMarginUsesThemeBackgroundWithoutChangingGeometry() {
        val pixel = Pixel17MonochromeRenderer.render(Glyph(), side, resources)
        val themed = Pixel17MonochromeRenderer.render(
            Glyph(), side, resources, matchThemeBackground = true
        )
        assertEquals(0, Color.alpha(themed.getPixel(0, 0)))
        assertEquals(255, Color.alpha(themed.getPixel(side / 2, side / 2)))
        assertEquals(visibleBounds(pixel, false), visibleBounds(themed, true))
        for (y in 0 until side) for (x in 0 until side) {
            assertEquals(255 - Color.alpha(pixel.getPixel(x, y)),
                Color.alpha(themed.getPixel(x, y)))
        }
        record("theme-background-legacy", themed)
    }

    @Test
    fun darkGeneratedMarginAlreadyShowsThemeBackgroundAndIsUnchanged() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.BLACK),
            Glyph(fraction = 0.4f, color = Color.WHITE))
        val pixel = Pixel17MonochromeRenderer.render(icon, side, resources)
        val themed = Pixel17MonochromeRenderer.render(
            icon, side, resources, matchThemeBackground = true
        )
        assertTrue(pixel.sameAs(themed))
        record("theme-background-adaptive", themed)
    }

    @Test
    fun nativeMonochromeRemainsUnchangedWhenGeneratedMarginsAreAligned() {
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.RED), Glyph(color = Color.BLUE),
            Glyph(fraction = 0.4f, color = Color.WHITE))
        val pixel = Pixel17MonochromeRenderer.render(icon, side, resources)
        val themed = Pixel17MonochromeRenderer.render(
            icon, side, resources, matchThemeBackground = true
        )
        assertTrue(pixel.sameAs(themed))
    }

}
