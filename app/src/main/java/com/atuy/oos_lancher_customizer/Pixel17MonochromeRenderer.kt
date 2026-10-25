package com.atuy.oos_lancher_customizer

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Geometry decoded from Pixel Launcher 17-907, not an older Launcher3 normalizer. */
internal object Pixel17MonochromeRenderer {
    fun render(source: Drawable, size: Int, resources: Resources): Bitmap {
        // Work on a private drawable: AdaptiveIconDrawable updates child bounds.
        val icon = source.constantState?.newDrawable(resources)?.mutate() ?: source
        val oldBounds = Rect(icon.bounds)
        try {
            val extra = AdaptiveIconDrawable.getExtraInsetFraction()
            val native = (icon as? AdaptiveIconDrawable)?.monochrome
            val layer = native ?: generate(icon, size, resources, extra)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
            // MonoIconThemeController#createThemedBitmap uses this exact negative
            // inset for BOTH native monochrome and generated alpha drawables.
            val inset = InsetDrawable(layer, -extra)
            inset.setBounds(0, 0, size, size)
            inset.draw(Canvas(bitmap))
            return bitmap
        } finally {
            icon.bounds = oldBounds
        }
    }

    private fun generate(icon: Drawable, size: Int, resources: Resources, extra: Float): Drawable {
        // Pixel BaseIconFactory.wrapToAdaptiveIcon has no alpha-hull/mask-match
        // branch. Measuring an OEM wrapper here would count its existing padding.
        val source = if (icon is AdaptiveIconDrawable) icon else {
            val insets = Pixel17MonoMath.legacyInsets(
                icon.intrinsicWidth, icon.intrinsicHeight,
                Pixel17MonoMath.legacyWrappedScale(extra)
            )
            AdaptiveIconDrawable(
                ColorDrawable(Color.WHITE),
                InsetDrawable(icon, insets.left, insets.top, insets.left, insets.top)
            )
        }
        val side = (size * 2f / (1f + 2f * extra)).roundToInt()
        val flat = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.BLACK)
        source.background?.let { it.setBounds(0, 0, side, side); it.draw(canvas) }
        source.foreground?.let { it.setBounds(0, 0, side, side); it.draw(canvas) }
        val alpha = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
        val matrix = ColorMatrix().apply {
            // Pixel initializes the identity matrix, then replaces its alpha row.
            array[15] = 0.3333f
            array[16] = 0.3333f
            array[17] = 0.3333f
            array[18] = 0f
            array[19] = 0f
        }
        Canvas(alpha).drawBitmap(flat, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = BlendMode.SRC
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        flat.recycle()
        val stride = alpha.rowBytes
        val padded = ByteArray(stride * side)
        alpha.copyPixelsToBuffer(ByteBuffer.wrap(padded))
        val pixels = ByteArray(side * side)
        for (y in 0 until side) padded.copyInto(pixels, y * side, y * stride, y * stride + side)
        Pixel17MonoMath.normalizeAndSmooth(pixels)
        for (y in 0 until side) pixels.copyInto(padded, y * stride, y * side, (y + 1) * side)
        alpha.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
        return BitmapDrawable(resources, alpha)
    }
}
