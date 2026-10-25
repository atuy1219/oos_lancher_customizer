package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Visual normalization from Launcher3 IconNormalizer:
 * sample the drawable alpha, form convex left/right border hulls, and
 * compute a shrink-only scale from the visible area and hull aspect.
 *
 * The source is sampled BEFORE the legacy icon is wrapped in an
 * AdaptiveIconDrawable. Measuring the monochrome result would lose the
 * source silhouette and make icons such as transit apps too large.
 */
internal object Pixel17IconNormalizer {
    private const val TAG = "Pixel17_Normalizer"
    private const val VISIBLE_ALPHA = 40
    private const val CIRCLE_AREA_BY_RECT = Math.PI.toFloat() / 4f
    private const val SQUARE_AREA_FACTOR = 375f / 576f
    private const val CIRCLE_AREA_FACTOR = 380f / 576f
    private const val SLOPE = (CIRCLE_AREA_FACTOR - SQUARE_AREA_FACTOR) /
        (1f - CIRCLE_AREA_BY_RECT)

    internal data class Result(val scale: Float, val matchesMask: Boolean)

    internal fun areaScale(hullArea: Float, boundsArea: Float, fullArea: Float): Float {
        if (hullArea <= 0f || boundsArea <= 0f || fullArea <= 0f) return 1f
        val hullRatio = hullArea / boundsArea
        val required = if (hullRatio < CIRCLE_AREA_BY_RECT) {
            CIRCLE_AREA_FACTOR
        } else {
            SQUARE_AREA_FACTOR + SLOPE * (1f - hullRatio)
        }
        val areaRatio = hullArea / fullArea
        return if (areaRatio > required) {
            sqrt(required / areaRatio).coerceIn(0f, 1f)
        } else 1f
    }

    /**
     * In-place AOSP convertToConvexArray. Missing scanlines between the
     * first and last nonempty row are interpolated by adjacent hull edges.
     */
    private fun convexBorder(coords: FloatArray, direction: Int, firstRow: Int, lastRow: Int) {
        val angles = FloatArray(coords.size - 1)
        var first = firstRow
        var last = -1
        var lastAngle = Float.MAX_VALUE
        for (i in firstRow + 1..lastRow) {
            if (coords[i] < 0f) continue
            var start = if (lastAngle == Float.MAX_VALUE) first else last
            if (lastAngle != Float.MAX_VALUE) {
                var angle = (coords[i] - coords[last]) / (i - last)
                if ((angle - lastAngle) * direction < 0f) {
                    while (start > first) {
                        start--
                        angle = (coords[i] - coords[start]) / (i - start)
                        if ((angle - angles[start]) * direction >= 0f) break
                    }
                }
            }
            lastAngle = (coords[i] - coords[start]) / (i - start)
            for (j in start until i) {
                angles[j] = lastAngle
                coords[j] = coords[start] + lastAngle * (j - start)
            }
            last = i
        }
    }

    internal fun measureAlpha(alpha: ByteArray, scanWidth: Int, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0 || scanWidth < width ||
            alpha.size < scanWidth * height) return 1f
        val left = FloatArray(height) { -1f }
        val right = FloatArray(height) { -1f }
        var top = -1
        var bottom = -1
        var minX = width
        var maxX = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((alpha[y * scanWidth + x].toInt() and 255) > VISIBLE_ALPHA) {
                    if (left[y] < 0f) left[y] = x.toFloat()
                    right[y] = x.toFloat()
                }
            }
            if (left[y] >= 0f) {
                if (top < 0) top = y
                bottom = y
                minX = min(minX, left[y].toInt())
                maxX = kotlin.math.max(maxX, right[y].toInt())
            }
        }
        if (top < 0 || maxX < 0) return 1f
        convexBorder(left, 1, top, bottom)
        convexBorder(right, -1, top, bottom)
        var hullArea = 0f
        for (y in top..bottom) {
            if (left[y] >= 0f && right[y] >= 0f) {
                hullArea += right[y] - left[y] + 1f
            }
        }
        val boundsArea = (bottom + 1 - top) * (maxX + 1 - minX).toFloat()
        return areaScale(hullArea, boundsArea, width * height.toFloat())
    }

    private fun matchesShape(
        alpha: ByteArray, stride: Int, left: Int, top: Int,
        right: Int, bottom: Int, iconMask: Path?
    ): Boolean {
        if (iconMask == null) return false
        val w = right + 1 - left
        val h = bottom + 1 - top
        if (w < 12 || h < 12 || abs(w - h) > w * 0.05f) return false
        return runCatching {
            val expected = Bitmap.createBitmap(stride, alpha.size / stride, Bitmap.Config.ALPHA_8)
            val shape = Path()
            val matrix = Matrix()
            matrix.setScale(w.toFloat(), h.toFloat())
            matrix.postTranslate(left.toFloat(), top.toFloat())
            iconMask.transform(matrix, shape)
            Canvas(expected).drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
            })
            val pixels = ByteArray(alpha.size)
            expected.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
            expected.recycle()
            // Ignore a two-pixel band along either edge, matching the
            // tolerance of Launcher3's mask outline removal.
            var mismatched = 0
            var compared = 0
            for (y in top + 2 until bottom - 1) {
                for (x in left + 2 until right - 1) {
                    val pos = y * stride + x
                    val a = (alpha[pos].toInt() and 255) > VISIBLE_ALPHA
                    val b = (pixels[pos].toInt() and 255) > VISIBLE_ALPHA
                    if (a != b) mismatched++
                    compared++
                }
            }
            compared > 0 && mismatched.toFloat() / compared < 0.005f
        }.getOrDefault(false)
    }

    fun measure(icon: Drawable, iconSize: Int, maskPath: Path?): Result {
        val maxSize = iconSize * 2
        var w = icon.intrinsicWidth
        var h = icon.intrinsicHeight
        if (w <= 0 || h <= 0) {
            w = maxSize
            h = maxSize
        } else if (w > maxSize || h > maxSize) {
            val largest = kotlin.math.max(w, h)
            w = maxSize * w / largest
            h = maxSize * h / largest
        }
        w = w.coerceIn(1, maxSize)
        h = h.coerceIn(1, maxSize)
        return runCatching {
            val bmp = Bitmap.createBitmap(maxSize, maxSize, Bitmap.Config.ALPHA_8)
            val originalBounds = Rect(icon.bounds)
            try {
                bmp.eraseColor(Color.TRANSPARENT)
                icon.setBounds(0, 0, w, h)
                icon.draw(Canvas(bmp))
            } finally {
                icon.bounds = originalBounds
            }
            val raw = ByteArray(maxSize * maxSize)
            bmp.copyPixelsToBuffer(ByteBuffer.wrap(raw))
            bmp.recycle()
            var left = w
            var right = -1
            var top = h
            var bottom = -1
            for (y in 0 until h) for (x in 0 until w) {
                if ((raw[y * maxSize + x].toInt() and 255) > VISIBLE_ALPHA) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
            val match = right >= left && bottom >= top &&
                matchesShape(raw, maxSize, left, top, right, bottom, maskPath)
            Result(measureAlpha(raw, maxSize, w, h), match)
        }.onFailure {
            Log.w(TAG, "Cannot normalize source drawable ${icon.javaClass.name}; using scale=1", it)
        }.getOrDefault(Result(1f, false))
    }

    /** The same area correction used for an actual AdaptiveIconDrawable mask. */
    fun adaptiveMaskScale(size: Int): Float = runCatching {
        val crop = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), null)
        crop.setBounds(0, 0, size, size)
        val region = Region()
        region.setPath(crop.iconMask, Region(0, 0, size, size))
        val it = RegionIterator(region)
        val rect = Rect()
        var area = 0f
        while (it.next(rect)) area += (rect.width() * rect.height()).toFloat()
        areaScale(area, area, size * size.toFloat())
    }.getOrDefault(1f)
}
