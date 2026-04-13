package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.LruCache
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.nio.ByteBuffer
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt

class ThemedIcons : XposedModule() {

    companion object {
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val BTV_CLASS = "com.android.launcher3.BubbleTextView"
        private const val PREVIEW_MANAGER_CLASS = "com.android.launcher3.folder.PreviewItemManager"
        private val ALLOWED_DISPLAYS = setOf(0, 2, 8, 9)
        private const val LOG_EVERY_N_HITS = 30L
    }

    private val hooksInstalled = AtomicBoolean(false)
    private val seq = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMiss = AtomicLong(0)
    private val failedKeys = object : LruCache<String, Boolean>(1024) {}

    // Max 16MB for themed icon bitmaps.
    private val iconCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        if (hooksInstalled.get()) return

        runCatching {
            installHooks(param.defaultClassLoader)
        }.onFailure {
            logError("critical: ${it.message}", it)
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        if (!hooksInstalled.compareAndSet(false, true)) return

        val bubbleTextViewClass = Class.forName(BTV_CLASS, false, classLoader)
        hookAllMethods(bubbleTextViewClass, "setIcon", SetIconHook())
        log("hooked BubbleTextView#setIcon")

        runCatching {
            val previewManagerClass = Class.forName(PREVIEW_MANAGER_CLASS, false, classLoader)
            hookAllMethods(previewManagerClass, "setDrawable", PreviewDrawableHook())
            log("hooked PreviewItemManager#setDrawable")
        }.onFailure {
            log("PreviewItemManager hook skipped: ${it.message}")
        }
    }

    private fun hookAllMethods(clazz: Class<*>, methodName: String, hooker: XposedInterface.Hooker) {
        val methods = LinkedHashSet<Method>()
        methods.addAll(clazz.declaredMethods.filter { it.name == methodName })
        methods.addAll(clazz.methods.filter { it.name == methodName })

        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker)
            }.onFailure {
                log("hook failed ${clazz.simpleName}#${method.name}: ${it.message}")
            }
        }
    }

    private inner class SetIconHook : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val view = chain.thisObject as? View ?: return chain.proceed()
            val args = chain.args.toMutableList()
            val drawable = args.firstOrNull() as? Drawable ?: return chain.proceed()
            val itemInfo = runCatching { view.tag }.getOrNull() ?: return chain.proceed()
            val pkg = resolvePackageName(itemInfo) ?: return chain.proceed()

            if (!shouldApplyToBubble(view, pkg)) return chain.proceed()

            val context = view.context.applicationContext ?: return chain.proceed()
            val isShortcut = isShortcutLikeItem(itemInfo)
            val source = resolveBestSourceDrawable(context, itemInfo, drawable)
            val themed = themedDrawable(context, pkg, source, isShortcut) ?: return chain.proceed()
            themed.bounds = drawable.bounds
            args[0] = themed

            val id = seq.incrementAndGet()
            log("[$id] setIcon themed pkg=$pkg view=${view.javaClass.simpleName} display=${safeDisplay(view)}")
            return chain.proceed(args.toTypedArray())
        }
    }

    private inner class PreviewDrawableHook : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()

            val previewParams = chain.args.firstOrNull() ?: return result
            val itemInfo = chain.args.getOrNull(1) ?: return result
            val pkg = resolvePackageName(itemInfo) ?: return result
            if (!shouldApplyToPackage(pkg)) return result

            val drawable = getObjectField(previewParams, "drawable") as? Drawable ?: return result

            val context = resolveContext(chain.thisObject) ?: return result
            val isShortcut = isShortcutLikeItem(itemInfo)
            val source = resolveBestSourceDrawable(context, itemInfo, drawable)
            val themed = themedDrawable(context, pkg, source, isShortcut) ?: return result
            themed.bounds = drawable.bounds
            setObjectField(previewParams, "drawable", themed)

            val id = seq.incrementAndGet()
            log("[$id] preview themed pkg=$pkg drawable=${drawable.javaClass.simpleName}")
            return result
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
        return runCatching { getIntField(view, "mDisplay") }.getOrNull()
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
        (getObjectField(itemInfo, "componentName") as? ComponentName)?.packageName?.let { return it }

        (callNoArgMethod(itemInfo, "getTargetComponent") as? ComponentName)?.packageName?.let { return it }

        (callNoArgMethod(itemInfo, "getMTargetComponent") as? ComponentName)?.packageName?.let { return it }

        (getObjectField(itemInfo, "packageName") as? String)?.takeIf { it.contains('.') }?.let { return it }

        (callNoArgMethod(itemInfo, "getTargetPackage") as? String)?.takeIf { it.contains('.') }?.let { return it }

        return null
    }

    private fun resolveComponentName(itemInfo: Any): ComponentName? {
        (getObjectField(itemInfo, "componentName") as? ComponentName)?.let { return it }

        (callNoArgMethod(itemInfo, "getTargetComponent") as? ComponentName)?.let { return it }

        (callNoArgMethod(itemInfo, "getMTargetComponent") as? ComponentName)?.let { return it }

        return null
    }

    private fun resolveUserHandle(itemInfo: Any): UserHandle? {
        return getObjectField(itemInfo, "user") as? UserHandle
    }

    private fun loadOriginalIcon(
        context: Context,
        component: ComponentName,
        user: UserHandle
    ): Drawable? {
        return runCatching {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val activityInfo =
                launcherApps.getActivityList(component.packageName, user).firstOrNull {
                    it.componentName == component
                }
            if (activityInfo != null) return@runCatching activityInfo.getIcon(0)
            context.packageManager.getActivityIcon(component)
        }.getOrNull()
    }

    private fun resolveBestSourceDrawable(context: Context, itemInfo: Any, fallback: Drawable): Drawable {
        val itemType = resolveItemType(itemInfo)
        if (itemType != 0 || isShortcutLikeItem(itemInfo)) {
            return fallback
        }
        val component = resolveComponentName(itemInfo) ?: return fallback
        val user = resolveUserHandle(itemInfo) ?: android.os.Process.myUserHandle()
        return loadOriginalIcon(context, component, user) ?: fallback
    }

    private fun resolveItemType(itemInfo: Any): Int? {
        return runCatching { getIntField(itemInfo, "itemType") }.getOrNull()
    }

    private fun isShortcutLikeItem(itemInfo: Any): Boolean {
        val itemType = resolveItemType(itemInfo)
        if (itemType == 1 || itemType == 6) return true

        val deepShortcut = runCatching {
            callNoArgMethod(itemInfo, "getDeepShortcutId") as? String
        }.getOrNull()
        if (!deepShortcut.isNullOrEmpty()) return true

        val shortcutId = getObjectField(itemInfo, "deepShortcutId") as? String
        return !shortcutId.isNullOrEmpty()
    }

    private fun findField(owner: Class<*>, name: String): Field? {
        var cls: Class<*>? = owner
        while (cls != null) {
            runCatching { cls.getDeclaredField(name) }.getOrNull()?.let { return it }
            cls = cls.superclass
        }
        return null
    }

    private fun getObjectField(target: Any, name: String): Any? {
        val field = findField(target.javaClass, name) ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(target)
        }.getOrNull()
    }

    private fun setObjectField(target: Any, name: String, value: Any?) {
        val field = findField(target.javaClass, name) ?: return
        runCatching {
            field.isAccessible = true
            field.set(target, value)
        }
    }

    private fun getIntField(target: Any, name: String): Int {
        val field = findField(target.javaClass, name)
            ?: throw NoSuchFieldException("Field not found: $name")
        field.isAccessible = true
        return field.getInt(target)
    }

    private fun callNoArgMethod(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val method = cls.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(target)
                }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    private fun circleMasked(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        val output = createBitmap(size, size)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size * 0.5f
        canvas.drawCircle(r, r, r, paint)
        return output
    }

    private fun themedDrawable(context: Context, pkg: String, original: Drawable, isShortcut: Boolean): Drawable? {
        val night = isNightMode(context)
        val sourceSig = buildSourceSignature(original)
        val key = "$pkg|$night|$isShortcut|$sourceSig"

        if (failedKeys.get(key) == true) {
            return null
        }

        val cached = iconCache.get(key)
        if (cached != null) {
            val hits = cacheHits.incrementAndGet()
            if (hits % LOG_EVERY_N_HITS == 0L) {
                log("cache hit=$hits miss=${cacheMiss.get()} sizeKB=${iconCache.size()}")
            }
            return cached.toDrawable(context.resources)
        }

        val bitmap = generateRawThemedBitmap(context, original, isShortcut)
        if (bitmap == null) {
            failedKeys.put(key, true)
            return null
        }
        iconCache.put(key, bitmap)
        cacheMiss.incrementAndGet()
        return bitmap.toDrawable(context.resources)
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
        L.d(msg)
    }

    private fun logError(msg: String, throwable: Throwable? = null) {
        L.e(msg, throwable)
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

    private fun generateRawThemedBitmap(context: Context, original: Drawable, isShortcut: Boolean): Bitmap? {
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
                MonochromeIconFactory(original, size, context.resources).create()
            }

        val size = 192
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val radius = size * 0.5f
        canvas.drawCircle(size * 0.5f, size * 0.5f, radius, bgPaint)

        monoDrawable.setTint(iconColor)
        monoDrawable.setTintMode(PorterDuff.Mode.SRC_IN)

        val monoIntrinsic = if (monoDrawable.intrinsicWidth > 0 && monoDrawable.intrinsicHeight > 0) {
            min(monoDrawable.intrinsicWidth, monoDrawable.intrinsicHeight)
        } else size

        val baseIconSize = when {
            isTrueAdaptive -> (size * 1.50f).toInt()
            isAdaptive && (isWrappedLegacyAdaptive || isLikelyBitmapAdaptive) -> size
            isAdaptive -> (size * 1.50f).toInt()
            else -> min(size, monoIntrinsic)
        }
        val iconSize = if (isShortcut) min(size, (baseIconSize * 2.3f).toInt()) else baseIconSize
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

        return circleMasked(bitmap)
    }

    private inner class MonochromeIconFactory(
        private val icon: Drawable,
        iconBitmapSize: Int,
        private val resources: android.content.res.Resources
    ) {
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
            return mAlphaBitmap.toDrawable(resources).apply {
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
