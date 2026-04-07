package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.LruCache
import android.view.View
import androidx.core.graphics.createBitmap
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt

class ThemedIcons : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "OOS16_ThemedIcons"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val BTV_CLASS = "com.android.launcher3.BubbleTextView"
        private const val PREVIEW_MANAGER_CLASS = "com.android.launcher3.folder.PreviewItemManager"
        private val ALLOWED_DISPLAYS = setOf(0, 2, 8, 9)
        private const val LOG_EVERY_N_HITS = 30L
    }

    private val seq = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMiss = AtomicLong(0)
    private val failedKeys = object : LruCache<String, Boolean>(1024) {}

    // Max 16MB for themed icon bitmaps.
    private val iconCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        runCatching {
            val bubbleTextViewClass = XposedHelpers.findClass(BTV_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(bubbleTextViewClass, "setIcon", SetIconHook())
            log("hooked BubbleTextView#setIcon")

            runCatching {
                val previewManagerClass = XposedHelpers.findClass(PREVIEW_MANAGER_CLASS, lpparam.classLoader)
                XposedBridge.hookAllMethods(previewManagerClass, "setDrawable", PreviewDrawableHook())
                log("hooked PreviewItemManager#setDrawable")
            }.onFailure {
                log("PreviewItemManager hook skipped: ${it.message}")
            }
        }.onFailure {
            log("critical: ${it.message}")
        }
    }

    private inner class SetIconHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val view = param.thisObject as? View ?: return
            val drawable = param.args.firstOrNull() as? Drawable ?: return
            val itemInfo = runCatching { view.tag }.getOrNull() ?: return
            val pkg = resolvePackageName(itemInfo) ?: return

            if (!shouldApplyToBubble(view, pkg)) return

            val context = view.context.applicationContext ?: return
            val themed = themedDrawable(context, pkg, drawable) ?: return
            themed.bounds = drawable.bounds
            param.args[0] = themed

            val id = seq.incrementAndGet()
            log("[$id] setIcon themed pkg=$pkg view=${view.javaClass.simpleName} display=${safeDisplay(view)}")
        }
    }

    private inner class PreviewDrawableHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val previewParams = param.args.firstOrNull() ?: return
            val itemInfo = param.args.getOrNull(1) ?: return
            val pkg = resolvePackageName(itemInfo) ?: return
            if (!shouldApplyToPackage(pkg)) return

            val drawable = runCatching {
                XposedHelpers.getObjectField(previewParams, "drawable") as? Drawable
            }.getOrNull() ?: return

            val context = resolveContext(param.thisObject) ?: return
            val themed = themedDrawable(context, pkg, drawable) ?: return
            themed.bounds = drawable.bounds
            XposedHelpers.setObjectField(previewParams, "drawable", themed)

            val id = seq.incrementAndGet()
            log("[$id] preview themed pkg=$pkg drawable=${drawable.javaClass.simpleName}")
        }
    }

    private fun shouldApplyToBubble(view: View, pkg: String): Boolean {
        if (!shouldApplyToPackage(pkg)) return false
        val display = safeDisplay(view)
        if (display != null && !ALLOWED_DISPLAYS.contains(display)) {
            log("skip display=$display pkg=$pkg")
            return false
        }
        return true
    }

    private fun shouldApplyToPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == TARGET_PACKAGE) return false
        return true
    }

    private fun safeDisplay(view: View): Int? {
        return runCatching { XposedHelpers.getIntField(view, "mDisplay") }.getOrNull()
    }

    private fun resolveContext(owner: Any?): Context? {
        (owner as? Context)?.let { return it.applicationContext }

        var cls: Class<*>? = owner?.javaClass
        while (cls != null) {
            for (field in cls.declaredFields) {
                if (!Context::class.java.isAssignableFrom(field.type)) continue
                val ctx = runCatching {
                    field.isAccessible = true
                    field.get(owner) as? Context
                }.getOrNull()
                if (ctx != null) return ctx.applicationContext
            }
            cls = cls.superclass
        }
        return null
    }

    private fun resolvePackageName(itemInfo: Any): String? {
        runCatching {
            XposedHelpers.getObjectField(itemInfo, "componentName") as? ComponentName
        }.getOrNull()?.packageName?.let { return it }

        runCatching {
            XposedHelpers.callMethod(itemInfo, "getTargetComponent") as? ComponentName
        }.getOrNull()?.packageName?.let { return it }

        runCatching {
            XposedHelpers.callMethod(itemInfo, "getMTargetComponent") as? ComponentName
        }.getOrNull()?.packageName?.let { return it }

        runCatching {
            XposedHelpers.getObjectField(itemInfo, "packageName") as? String
        }.getOrNull()?.takeIf { it.contains('.') }?.let { return it }

        runCatching {
            XposedHelpers.callMethod(itemInfo, "getTargetPackage") as? String
        }.getOrNull()?.takeIf { it.contains('.') }?.let { return it }

        return null
    }

    private fun themedDrawable(context: Context, pkg: String, original: Drawable): Drawable? {
        val night = isNightMode(context)
        val sourceSig = buildSourceSignature(original)
        val key = "$pkg|$night|$sourceSig"

        if (failedKeys.get(key) == true) {
            return null
        }

        val cached = iconCache.get(key)
        if (cached != null) {
            val hits = cacheHits.incrementAndGet()
            if (hits % LOG_EVERY_N_HITS == 0L) {
                log("cache hit=$hits miss=${cacheMiss.get()} sizeKB=${iconCache.size()}")
            }
            return BitmapDrawable(context.resources, cached)
        }

        val bitmap = generateRawThemedBitmap(context, original)
        if (bitmap == null) {
            failedKeys.put(key, true)
            return null
        }
        iconCache.put(key, bitmap)
        cacheMiss.incrementAndGet()
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun buildSourceSignature(source: Drawable): String {
        val state = source.constantState?.hashCode() ?: 0
        return "${source.javaClass.name}#$state#${source.intrinsicWidth}x${source.intrinsicHeight}"
    }

    private fun isNightMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun toSoftwareBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    private fun extractSoftwareBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            return toSoftwareBitmap(drawable.bitmap)
        }
        val reflected = runCatching {
            val method = drawable.javaClass.getMethod("getBitmap")
            method.isAccessible = true
            method.invoke(drawable) as? Bitmap
        }.getOrNull()
        return toSoftwareBitmap(reflected)
    }

    private fun drawDrawableSafely(drawable: Drawable, canvas: Canvas, size: Int): Boolean {
        return runCatching {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            true
        }.getOrElse { error ->
            val message = error.message ?: ""
            if (!message.contains("hardware bitmaps", ignoreCase = true)) return false

            val softwareBitmap = extractSoftwareBitmap(drawable) ?: return false
            val src = Rect(0, 0, softwareBitmap.width, softwareBitmap.height)
            val dst = Rect(0, 0, size, size)
            canvas.drawBitmap(softwareBitmap, src, dst, null)
            true
        }
    }

    private fun generateRawThemedBitmap(context: Context, original: Drawable): Bitmap? {
        val isDarkMode = isNightMode(context)
        val bgColor = context.getColor(
            if (isDarkMode) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100
        )
        val iconColor = context.getColor(
            if (isDarkMode) android.R.color.system_accent1_100 else android.R.color.system_accent1_600
        )

        val isAdaptive = original is AdaptiveIconDrawable
        val isWrappedLegacyAdaptive = isAdaptive && (original.background is ColorDrawable) && (original.foreground is BitmapDrawable)
        val isTrueAdaptive = isAdaptive && original.monochrome != null
        val isLikelyBitmapAdaptive = isAdaptive && original.monochrome == null && (
            original.background is BitmapDrawable || original.foreground is BitmapDrawable || original.foreground == null
        )

        val monoDrawable: Drawable =
            if (isTrueAdaptive) {
                original.monochrome!!.mutate()
            } else {
                val size = if (original.intrinsicWidth > 0) original.intrinsicWidth else 108
                MonochromeIconFactory(original, size).create()
            }

        val size = 192
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        canvas.drawColor(bgColor)

        monoDrawable.setTint(iconColor)
        monoDrawable.setTintMode(PorterDuff.Mode.SRC_IN)

        val monoIntrinsic = if (monoDrawable.intrinsicWidth > 0 && monoDrawable.intrinsicHeight > 0) {
            min(monoDrawable.intrinsicWidth, monoDrawable.intrinsicHeight)
        } else size

        val iconSize = when {
            isTrueAdaptive -> (size * 1.50f).toInt()
            isAdaptive && (isWrappedLegacyAdaptive || isLikelyBitmapAdaptive) -> size
            isAdaptive -> (size * 1.50f).toInt()
            else -> min(size, monoIntrinsic)
        }
        val offset = (size - iconSize) / 2

        runCatching {
            monoDrawable.setBounds(offset, offset, offset + iconSize, offset + iconSize)
            monoDrawable.draw(canvas)
        }.onFailure {
            val fallback = extractSoftwareBitmap(monoDrawable)
            if (fallback != null) {
                canvas.drawBitmap(
                    fallback,
                    Rect(0, 0, fallback.width, fallback.height),
                    Rect(offset, offset, offset + iconSize, offset + iconSize),
                    null
                )
            } else {
                log("skip mono draw: ${it.message}")
                return null
            }
        }

        return bitmap
    }

    private inner class MonochromeIconFactory(private val icon: Drawable, iconBitmapSize: Int) {
        private val mBitmapSize: Int
        private val mEdgePixelLength: Int
        private val mFlatBitmap: Bitmap
        private val mFlatCanvas: Canvas
        private val mAlphaBitmap: Bitmap
        private val mAlphaCanvas: Canvas
        private val mPixels: ByteArray
        private val mCopyPaint: Paint
        private val mDrawPaint: Paint
        private val mSrcRect: Rect

        init {
            val extraFactor = AdaptiveIconDrawable.getExtraInsetFraction()
            val viewPortScale = 1f / (1 + 2 * extraFactor)
            mBitmapSize = (iconBitmapSize * 2 * viewPortScale).roundToInt()
            mEdgePixelLength = mBitmapSize * (mBitmapSize - iconBitmapSize) / 2
            mPixels = ByteArray(mBitmapSize * mBitmapSize)

            mFlatBitmap = createBitmap(mBitmapSize, mBitmapSize)
            mFlatCanvas = Canvas(mFlatBitmap)
            mAlphaBitmap = createBitmap(mBitmapSize, mBitmapSize, Bitmap.Config.ALPHA_8)
            mAlphaCanvas = Canvas(mAlphaBitmap)

            mDrawPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
            mSrcRect = Rect(0, 0, mBitmapSize, mBitmapSize)

            mCopyPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                blendMode = BlendMode.SRC
                val satMatrix = ColorMatrix().apply { setSaturation(0f) }
                val vals = satMatrix.array
                vals[15] = 0.3333f; vals[16] = 0.3333f; vals[17] = 0.3333f
                vals[18] = 0f; vals[19] = 0f
                colorFilter = ColorMatrixColorFilter(vals)
            }
        }

        fun create(): Drawable {
            convertMono()
            return BitmapDrawable(null, mAlphaBitmap).apply {
                setBounds(0, 0, mBitmapSize, mBitmapSize)
            }
        }

        private fun convertMono() {
            if (icon is AdaptiveIconDrawable) {
                mFlatCanvas.drawColor(Color.BLACK)
                drawDrawable(icon.background)
                drawDrawable(icon.foreground)
            } else {
                mFlatCanvas.drawColor(Color.WHITE)
                drawDrawable(icon)
            }
            generateMono()
        }

        private fun drawDrawable(drawable: Drawable?) {
            drawable?.apply {
                if (!drawDrawableSafely(this, mFlatCanvas, mBitmapSize)) {
                    log("skip drawDrawable in mono factory: ${javaClass.name}")
                }
            }
        }

        private fun generateMono() {
            mAlphaCanvas.drawBitmap(mFlatBitmap, 0f, 0f, mCopyPaint)
            val buffer = ByteBuffer.wrap(mPixels)
            buffer.rewind()
            mAlphaBitmap.copyPixelsToBuffer(buffer)

            var min = 0xFF
            var max = 0
            for (b in mPixels) {
                val v = b.toInt() and 0xFF
                if (v < min) min = v
                if (v > max) max = v
            }

            if (min < max) {
                val range = (max - min).toFloat()
                var sum = 0
                for (i in 0 until mEdgePixelLength) {
                    sum += (mPixels[i].toInt() and 0xFF)
                    sum += (mPixels[mPixels.size - 1 - i].toInt() and 0xFF)
                }
                val edgeAverage = sum / (mEdgePixelLength * 2f)
                val edgeMapped = (edgeAverage - min) / range
                val flipColor = edgeMapped > 0.5f

                for (i in mPixels.indices) {
                    val p = mPixels[i].toInt() and 0xFF
                    val p2 = ((p - min) * 0xFF / range).roundToInt()
                    mPixels[i] = (if (flipColor) (255 - p2) else p2).toByte()
                }
                buffer.rewind()
                mAlphaBitmap.copyPixelsFromBuffer(buffer)
            }
        }
    }
}
