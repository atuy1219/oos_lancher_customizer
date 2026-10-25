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
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import android.util.LruCache
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt

class ThemedIcons : XposedModule() {

    companion object {
        private const val TAG = "OOS16_ThemedIcons"
        private const val TARGET_PACKAGE = "com.android.launcher"
        private const val BTV_CLASS = "com.android.launcher3.BubbleTextView"
        private const val PREVIEW_MANAGER_CLASS = "com.android.launcher3.folder.PreviewItemManager"
        private const val BIG_PREVIEW_MANAGER_CLASS =
            "com.android.launcher3.folder.big.BigFolderPreviewItemManager"
        private const val BIG_FOLDER_ITEM_CLASS =
            "com.android.launcher3.folder.big.BigFolderItemIcon"
        private const val FLOATING_ICON_VIEW_CLASS = "com.android.launcher3.views.FloatingIconView"
        private const val LOG_EVERY_N_HITS = 30L
    }

    private val seq = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMiss = AtomicLong(0)
    private val failedKeys = object : LruCache<String, Boolean>(1024) {}

    // getDrawableByIndex may run every frame. Do not allocate a new themed
    // Drawable from ConstantState on every draw.
    private data class BigFolderCacheEntry(
        val original: WeakReference<Drawable>,
        val itemKey: String,
        val themed: WeakReference<Drawable>
    )
    private val bigFolderViewCache =
        Collections.synchronizedMap(WeakHashMap<View, BigFolderCacheEntry>())
    @Volatile
    private var hooksInstalled = false

    // Max 16MB for themed icon bitmaps.
    private val iconCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE || hooksInstalled) return

        runCatching {
            installHooks(param.defaultClassLoader)
            hooksInstalled = true
        }.onFailure {
            moduleLog("critical: ${it.message}", it)
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        val bubbleTextViewClass = Class.forName(BTV_CLASS, false, classLoader)
        hookAllNamedMethods(bubbleTextViewClass, "setIcon", "bubble-setIcon") { chain ->
            val args = chain.args.toTypedArray()
            beforeBubbleSetIcon(chain.thisObject, args)
            chain.proceed(args)
        }
        moduleLog("hooked BubbleTextView#setIcon")

        runCatching {
            val previewManagerClass = Class.forName(PREVIEW_MANAGER_CLASS, false, classLoader)
            hookAllNamedMethods(previewManagerClass, "setDrawable", "folder-setDrawable") { chain ->
                val args = chain.args.toTypedArray()
                val result = chain.proceed()
                afterPreviewSetDrawable(chain.thisObject, args)
                result
            }
            moduleLog("hooked PreviewItemManager#setDrawable")
        }.onFailure {
            moduleLog("PreviewItemManager hook skipped: ${it.message}")
        }

        // BigFolderItemIcon is a plain View rather than BubbleTextView. Its
        // Drawable cannot be captured by the ordinary setIcon/preview hooks.
        // Register the exact ColorOS 16.6.20 image paths, including the preview
        // manager override that does not have to call super#setDrawable.
        runCatching {
            val bigManager = Class.forName(BIG_PREVIEW_MANAGER_CLASS, false, classLoader)
            hookAllNamedMethods(bigManager, "setDrawable", "big-preview-setDrawable") { chain ->
                val args = chain.args.toTypedArray()
                val result = chain.proceed(args)
                afterPreviewSetDrawable(chain.thisObject, args)
                result
            }
            moduleLog("hooked BigFolderPreviewItemManager#setDrawable")
        }.onFailure {
            moduleLog("big preview hook skipped: ${it.message}", it)
        }

        runCatching {
            val iconClass = Class.forName(BIG_FOLDER_ITEM_CLASS, false, classLoader)
            hookAllNamedMethods(iconClass, "setDrawable", "big-item-setDrawable") { chain ->
                val args = chain.args.toTypedArray()
                beforeBigFolderSetDrawable(chain.thisObject, args)
                chain.proceed(args)
            }
            for (methodName in listOf("getDrawable", "getDrawableByIndex")) {
                hookAllNamedMethods(iconClass, methodName, "big-item-$methodName") { chain ->
                    val result = chain.proceed()
                    themedBigFolderDrawable(chain.thisObject, result as? Drawable) ?: result
                }
            }
            // setItemInfo/setParentFolderIcon may be called after setDrawable.
            // Reconcile the stored field once both bindings become available.
            for (methodName in listOf("setItemInfo", "setParentFolderIcon")) {
                hookAllNamedMethods(iconClass, methodName, "big-item-$methodName") { chain ->
                    val result = chain.proceed()
                    reconcileBigFolderItem(chain.thisObject)
                    result
                }
            }
            moduleLog("hooked BigFolderItemIcon drawable/binding paths")
        }.onFailure {
            moduleLog("BigFolderItemIcon hooks skipped: ${it.message}", it)
        }

        runCatching {
            val floatingIconViewClass = Class.forName(FLOATING_ICON_VIEW_CLASS, false, classLoader)
            hookAllNamedMethods(floatingIconViewClass, "setIcon", "floating-setIcon") { chain ->
                val args = chain.args.toTypedArray()
                beforeFloatingSetIcon(chain.thisObject, args)
                chain.proceed(args)
            }
            moduleLog("hooked FloatingIconView#setIcon")
        }.onFailure {
            moduleLog("FloatingIconView hook skipped: ${it.message}")
        }


    }

    private fun hookAllNamedMethods(
        clazz: Class<*>,
        methodName: String,
        hookIdPrefix: String,
        interceptor: (XposedInterface.Chain) -> Any?
    ) {
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        check(methods.isNotEmpty()) { "${clazz.name}#$methodName not found" }

        methods.forEach { method ->
            method.isAccessible = true
            val signature = method.parameterTypes.joinToString(",") { it.name }
            hook(method)
                .setId("$hookIdPrefix($signature)")
                .intercept { chain -> interceptor(chain) }
        }
    }

    private fun beforeBubbleSetIcon(owner: Any?, args: Array<Any?>) {
        val view = owner as? View ?: return
        val drawable = args.firstOrNull() as? Drawable ?: return
        val itemInfo = ThemedIconPolicy.itemForView(view)
            ?: readField(view, "mInfo")
            ?: return
        val pkg = resolvePackageName(itemInfo) ?: return

        if (!shouldApplyToPackage(pkg) || !ThemedIconPolicy.shouldThemeView(view, itemInfo)) return

        // Skip already themed drawables to avoid repeated monochrome conversion.
        if (ThemedIconPolicy.isThemedDrawable(drawable)) {
            ThemedIconPolicy.rememberDrawable(drawable, itemInfo)
            return
        }

        val context = view.context.applicationContext ?: return
        val isShortcut = isShortcutLikeItem(itemInfo)
        val source = resolveBestSourceDrawable(context, itemInfo, drawable)
        val themed = themedDrawable(context, pkg, source, isShortcut, resolveComponentName(itemInfo)) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        args[0] = themed

        val id = seq.incrementAndGet()
        moduleLog("[$id] setIcon themed pkg=$pkg view=${view.javaClass.simpleName} display=${safeDisplay(view)}")
    }

    private fun beforeBigFolderSetDrawable(owner: Any?, args: Array<Any?>) {
        val original = args.firstOrNull() as? Drawable ?: return
        val themed = themedBigFolderDrawable(owner, original) ?: return
        args[0] = themed
    }

    private fun reconcileBigFolderItem(owner: Any?) {
        val view = owner as? View ?: return
        val original = readField(view, "drawable") as? Drawable ?: return
        val themed = themedBigFolderDrawable(view, original) ?: return
        if (themed !== original) {
            writeField(view, "drawable", themed)
            view.invalidate()
        }
    }

    private fun themedBigFolderDrawable(owner: Any?, original: Drawable?): Drawable? {
        val view = owner as? View ?: return null
        val source = original ?: return null
        val info = ThemedIconPolicy.itemForView(view) ?: return null
        if (!ThemedIconPolicy.shouldThemeView(view, info)) return null
        val pkg = resolvePackageName(info) ?: return null
        if (!shouldApplyToPackage(pkg)) return null

        if (ThemedIconPolicy.isThemedDrawable(source)) {
            ThemedIconPolicy.rememberDrawable(source, info)
            return source
        }
        val key = ThemedIconPolicy.itemKey(info)
        val cached = bigFolderViewCache[view]
        val reused = cached?.themed?.get()
        if (cached != null && cached.original.get() === source &&
            cached.itemKey == key && reused != null) {
            reused.bounds = source.bounds
            return reused
        }

        // A BigFolderItemIcon getter can be called during animations. Prefer
        // the exact themed snapshot already recorded for the item rather than
        // applying the monochrome filter twice or loading the package again.
        val existing = ThemedIconPolicy.themedStateFor(info)
        if (existing != null) {
            val restored = existing.newDrawable(view.resources).mutate()
            restored.bounds = source.bounds
            ThemedIconPolicy.rememberDrawable(restored, info)
            bigFolderViewCache[view] = BigFolderCacheEntry(WeakReference(source), key, WeakReference(restored))
            return restored
        }

        val context = view.context.applicationContext ?: view.context
        val isShortcut = isShortcutLikeItem(info)
        val originalSource = resolveBestSourceDrawable(context, info, source)
        val themed = themedDrawable(context, pkg, originalSource, isShortcut, resolveComponentName(info)) ?: return null
        themed.bounds = source.bounds
        ThemedIconPolicy.rememberDrawable(themed, info)
        bigFolderViewCache[view] = BigFolderCacheEntry(WeakReference(source), key, WeakReference(themed))
        return themed
    }

    private fun afterPreviewSetDrawable(owner: Any?, args: Array<Any?>) {
        val previewParams = args.firstOrNull() ?: return
        val itemInfo = args.getOrNull(1) ?: return
        val pkg = resolvePackageName(itemInfo) ?: return
        if (!shouldApplyToPackage(pkg) || !ThemedIconPolicy.shouldThemeFolderPreview(owner, itemInfo)) return

        val drawable = readField(previewParams, "drawable") as? Drawable ?: return
        if (ThemedIconPolicy.isThemedDrawable(drawable)) {
            ThemedIconPolicy.rememberDrawable(drawable, itemInfo)
            return
        }
        val context = resolveContext(owner) ?: return
        val isShortcut = isShortcutLikeItem(itemInfo)
        val source = resolveBestSourceDrawable(context, itemInfo, drawable)
        val themed = themedDrawable(context, pkg, source, isShortcut, resolveComponentName(itemInfo)) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        writeField(previewParams, "drawable", themed)

        val id = seq.incrementAndGet()
        moduleLog("[$id] preview themed pkg=$pkg drawable=${drawable.javaClass.simpleName}")
    }

    private fun beforeFloatingSetIcon(owner: Any?, args: Array<Any?>) {
        val view = owner as? View ?: return
        val drawable = args.firstOrNull() as? Drawable ?: return
        val itemInfo = resolveFloatingItemInfo(owner) ?: return
        val pkg = resolvePackageName(itemInfo) ?: return
        val originalView = readField(owner, "mOriginalIcon") as? View
        if (!shouldApplyToPackage(pkg) ||
            !ThemedIconPolicy.shouldThemeSurfaceTarget(originalView, itemInfo)) return

        val context = view.context.applicationContext ?: return
        val isShortcut = isShortcutLikeItem(itemInfo)
        val source = resolveBestSourceDrawable(context, itemInfo, drawable)
        val themed = themedDrawable(context, pkg, source, isShortcut, resolveComponentName(itemInfo)) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        args[0] = themed

        // Keep OOS' load result coherent with the drawable passed to ClipIconView.
        val loadResult = readField(owner, "mIconLoadResult")
        if (loadResult != null) {
            runCatching { writeField(loadResult, "drawable", themed) }
            runCatching { writeField(loadResult, "isThemed", true) }
        }

        val id = seq.incrementAndGet()
        moduleLog("[$id] floating themed pkg=$pkg drawable=${drawable.javaClass.simpleName}")
    }

    private fun resolveFloatingItemInfo(owner: Any): Any? {
        val loadResult = readField(owner, "mIconLoadResult")
        if (loadResult != null) {
            readField(loadResult, "itemInfo")?.let { return it }
        }

        val originalIcon = readField(owner, "mOriginalIcon") as? View
        if (originalIcon != null) {
            runCatching { originalIcon.tag }.getOrNull()?.let { return it }
        }

        val btvDrawable = readField(owner, "mBtvDrawable") as? View
        if (btvDrawable != null) {
            runCatching { btvDrawable.tag }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun shouldApplyToPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == TARGET_PACKAGE) return false
        return true
    }

    private fun safeDisplay(view: View): Int? {
        return (readField(view, "mDisplay") as? Number)?.toInt()
    }

    private fun findField(owner: Any, name: String): Field? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val field = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) return field
            cls = current.superclass
        }
        return null
    }

    private fun readField(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return runCatching { findField(owner, name)?.get(owner) }.getOrNull()
    }

    private fun writeField(owner: Any, name: String, value: Any?) {
        val field = findField(owner, name) ?: return
        field.set(owner, value)
    }

    private fun findNoArgMethod(owner: Any, name: String): Method? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val method = current.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) {
                method.isAccessible = true
                return method
            }
            cls = current.superclass
        }
        return null
    }

    private fun invokeNoArg(owner: Any, name: String): Any? {
        return runCatching { findNoArgMethod(owner, name)?.invoke(owner) }.getOrNull()
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
        (readField(itemInfo, "componentName") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (invokeNoArg(itemInfo, "getTargetComponent") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (invokeNoArg(itemInfo, "getMTargetComponent") as? ComponentName)
            ?.packageName
            ?.let { return it }

        (readField(itemInfo, "packageName") as? String)
            ?.takeIf { it.contains('.') }
            ?.let { return it }

        (invokeNoArg(itemInfo, "getTargetPackage") as? String)
            ?.takeIf { it.contains('.') }
            ?.let { return it }

        return null
    }

    private fun resolveComponentName(itemInfo: Any): ComponentName? {
        (readField(itemInfo, "componentName") as? ComponentName)?.let { return it }
        (invokeNoArg(itemInfo, "getTargetComponent") as? ComponentName)?.let { return it }
        (invokeNoArg(itemInfo, "getMTargetComponent") as? ComponentName)?.let { return it }
        return null
    }

    private fun resolveUserHandle(itemInfo: Any): UserHandle? {
        return readField(itemInfo, "user") as? UserHandle
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

    private fun resolveBestSourceDrawable(
        context: Context,
        itemInfo: Any,
        fallback: Drawable
    ): Drawable {
        val itemType = resolveItemType(itemInfo)
        if (itemType != 0 || isShortcutLikeItem(itemInfo)) {
            return fallback
        }
        val component = resolveComponentName(itemInfo) ?: return fallback
        val user = resolveUserHandle(itemInfo) ?: android.os.Process.myUserHandle()
        return loadOriginalIcon(context, component, user) ?: fallback
    }

    private fun resolveItemType(itemInfo: Any): Int? {
        return (readField(itemInfo, "itemType") as? Number)?.toInt()
    }

    private fun isShortcutLikeItem(itemInfo: Any): Boolean {
        val itemType = resolveItemType(itemInfo)
        if (itemType == 1 || itemType == 6) return true

        val deepShortcut = invokeNoArg(itemInfo, "getDeepShortcutId") as? String
        if (!deepShortcut.isNullOrEmpty()) return true

        val shortcutId = readField(itemInfo, "deepShortcutId") as? String
        return !shortcutId.isNullOrEmpty()
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

    private fun themedDrawable(
        context: Context,
        pkg: String,
        original: Drawable,
        isShortcut: Boolean,
        component: ComponentName?
    ): Drawable? {
        val night = isNightMode(context)
        val sourceSig = buildSourceSignature(original)
        val hasLegacy = !isShortcut && Oos15MonochromeAssets.hasArtwork(context, pkg)
        // Keep the OOS15 original and native app monochrome ahead of the Pixel
        // Launcher map. Apps not covered by either map keep their old cache keys.
        val hasNativeMono = (original as? AdaptiveIconDrawable)?.monochrome != null
        val hasPixel = !isShortcut && !hasLegacy && !hasNativeMono &&
            PixelLauncherThemedAssets.hasArtwork(pkg)
        val hasSynthetic = !hasLegacy && !hasPixel && !hasNativeMono
        val key = when {
            isShortcut -> "$pkg|$night|$isShortcut|$sourceSig|pixel17-shortcut-v5-circle"
            hasLegacy -> "$pkg|$night|$isShortcut|$sourceSig|oos15-asset-v3-scale150|${component?.className}"
            hasPixel -> "$pkg|$night|$isShortcut|$sourceSig|pixel-theme-17-907-inset20-scale060|${component?.className}"
            hasSynthetic -> "$pkg|$night|$isShortcut|$sourceSig|pixel17-mono-v4-fullbleed"
            else -> "$pkg|$night|$isShortcut|$sourceSig|native-mono-v4-extra-inset"
        }

        if (failedKeys.get(key) == true) {
            return null
        }

        val cached = iconCache.get(key)
        if (cached != null) {
            val hits = cacheHits.incrementAndGet()
            if (hits % LOG_EVERY_N_HITS == 0L) {
                moduleLog("cache hit=$hits miss=${cacheMiss.get()} sizeKB=${iconCache.size()}")
            }
            return cached.toDrawable(context.resources)
        }

        val bitmap = generateRawThemedBitmap(context, pkg, original, isShortcut, component)
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

    private fun moduleLog(msg: String, throwable: Throwable? = null) {
        if (throwable == null) {
            log(Log.INFO, TAG, msg)
        } else {
            log(Log.ERROR, TAG, msg, throwable)
        }
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

    private fun generateRawThemedBitmap(
        context: Context,
        pkg: String,
        original: Drawable,
        isShortcut: Boolean,
        component: ComponentName?
    ): Bitmap? {
        val legacyBitmap = if (!isShortcut) Oos15MonochromeAssets.load(context, pkg, component) else null
        val hasNativeMono = (original as? AdaptiveIconDrawable)?.monochrome != null
        val pixelBitmap = if (!isShortcut && legacyBitmap == null && !hasNativeMono) {
            PixelLauncherThemedAssets.load(pkg)
        } else null
        val isDarkMode = isNightMode(context)
        val bgColor = context.getColor(
            if (isDarkMode) android.R.color.system_neutral1_800
            else android.R.color.system_accent1_100
        )
        val iconColor = context.getColor(
            if (isDarkMode) android.R.color.system_accent1_100
            else android.R.color.system_accent1_600
        )

        val isTrueAdaptive =
            original is AdaptiveIconDrawable && original.monochrome != null

        val monoDrawable: Drawable =
            if (legacyBitmap != null) {
                BitmapDrawable(context.resources, legacyBitmap).mutate()
            } else if (isTrueAdaptive) {
                original.monochrome!!.mutate()
            } else if (pixelBitmap != null) {
                BitmapDrawable(context.resources, pixelBitmap).mutate()
            } else {
                // Pixel BaseIconFactory passes its fixed output icon size to
                // MonochromeIconFactory, not the source app icon's intrinsic size.
                MonochromeIconFactory(original, 192, context.resources, isShortcut).create()
            }

        val size = 192
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val radius = size * 0.5f
        canvas.drawCircle(size * 0.5f, size * 0.5f, radius, bgPaint)

        monoDrawable.setTint(iconColor)
        monoDrawable.setTintMode(PorterDuff.Mode.SRC_IN)

        val baseIconSize = when {
            // OEM PNG contains intrinsic padding: retain the proven 1.5x zoom.
            legacyBitmap != null -> (
                size * Oos15MonochromeAssets.glyphScale(pkg)
            ).roundToInt()
            // Both Pixel and Oplus IconProvider pad theme fallback vectors
            // by 20% on each side. AdaptiveIconDrawable's extra foreground
            // inset is compensated at render time: net 60% of this circle.
            pixelBitmap != null -> (
                size * PixelLauncherThemedAssets.GLYPH_SCALE
            ).roundToInt()
            // A monochrome layer uses the adaptive foreground viewport, which
            // includes extra inset on every side. Compensate that inset here.
            // IconNormalizer's OUTER icon scale cannot replace this transform:
            // doing so shrinks native glyphs and exposes the generated bitmap's
            // rectangular boundary inside the final icon mask.
            else -> Pixel17MonoMath.layerSize(
                size, AdaptiveIconDrawable.getExtraInsetFraction()
            )
        }
        // The generated foreground is resized before grayscale conversion;
        // its opaque background must keep covering the entire layer.
        val previousIconSize =
            if (isShortcut) min(size, (baseIconSize * 2.3f).toInt()) else baseIconSize
        val iconSize = Pixel17MonoMath.outputSize(previousIconSize, isShortcut)
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
                moduleLog("skip mono draw: ${it.message}")
                return null
            }
        }

        return circleMasked(bitmap)
    }

    private inner class MonochromeIconFactory(
        private val icon: Drawable,
        private val iconBitmapSize: Int,
        private val resources: android.content.res.Resources,
        private val isShortcut: Boolean
    ) {
        private val mBitmapSize: Int
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
                vals[15] = 0.3333f
                vals[16] = 0.3333f
                vals[17] = 0.3333f
                vals[18] = 0f
                vals[19] = 0f
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
            // Pixel BaseIconFactory#wrapToAdaptiveIcon: nonadaptive icons are
            // normalized within an AdaptiveIconDrawable, backed by white.
            val source = if (icon is AdaptiveIconDrawable) {
                icon
            } else {
                val crop = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), null)
                crop.setBounds(0, 0, iconBitmapSize, iconBitmapSize)
                val normal = Pixel17IconNormalizer.measure(
                    icon, iconBitmapSize, crop.iconMask
                )
                val extra = AdaptiveIconDrawable.getExtraInsetFraction()
                val scale = if (normal.matchesMask) {
                    // A launcher-mask-shaped legacy source should not receive
                    // the heavy generic legacy wrapper padding.
                    1f - extra
                } else {
                    // Same normalizeAndWrapToAdaptiveIcon path as Pixel 17.
                    normal.scale * Pixel17MonoMath.legacyWrappedScale(extra)
                }
                val insets = Pixel17MonoMath.legacyInsets(
                    icon.intrinsicWidth, icon.intrinsicHeight, scale
                )
                AdaptiveIconDrawable(
                    ColorDrawable(Color.WHITE),
                    InsetDrawable(icon, insets.left, insets.top, insets.left, insets.top)
                )
            }
            mFlatCanvas.drawColor(Color.BLACK)
            drawDrawable(source.background)
            // Keep background full bleed. Scaling the finished alpha bitmap
            // also scales its opaque matte and leaves a visible square edge.
            val save = mFlatCanvas.save()
            val scale = Pixel17MonoMath.foregroundScale(isShortcut)
            mFlatCanvas.scale(scale, scale, mBitmapSize / 2f, mBitmapSize / 2f)
            try {
                drawDrawable(source.foreground)
            } finally {
                mFlatCanvas.restoreToCount(save)
            }
            generateMono()
        }

        private fun drawDrawable(drawable: Drawable?) {
            drawable?.apply {
                if (!drawDrawableSafely(this, mFlatCanvas, mBitmapSize)) {
                    moduleLog("skip drawDrawable in mono factory: ${javaClass.name}")
                }
            }
        }

        private fun generateMono() {
            mAlphaCanvas.drawBitmap(mFlatBitmap, 0f, 0f, mCopyPaint)
            val buffer = ByteBuffer.wrap(mPixels)
            buffer.rewind()
            mAlphaBitmap.copyPixelsToBuffer(buffer)

            // Pixel Launcher 17-907: normalize 0..255, then apply its two
            // quadratic half-range curves. The previous OOS implementation's
            // edge-pixel-driven polarity inversion does not exist in Pixel.
            Pixel17MonoMath.normalizeAndSmooth(mPixels)
            buffer.rewind()
            mAlphaBitmap.copyPixelsFromBuffer(buffer)
        }
    }
}
