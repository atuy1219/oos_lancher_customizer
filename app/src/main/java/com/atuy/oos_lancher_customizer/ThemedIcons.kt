package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
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
    }

    private val iconCache = IconBitmapCache()
    @Volatile private var environment: IconEnvironment? = null

    private data class Binding(
        val key: String, val state: Drawable.ConstantState?, val original: WeakReference<Drawable>,
        val version: Int?
    ) {
        fun restore(context: Context): Drawable? =
            state?.newDrawable(context.resources)?.mutate() ?: original.get()
    }
    private data class PreviewBinding(val owner: WeakReference<Any>, val item: Any, val source: Binding?)
    private val bubbles = Collections.synchronizedMap(WeakHashMap<View, Binding>())
    private val folders = Collections.synchronizedMap(WeakHashMap<View, Binding>())
    private val previews = Collections.synchronizedMap(WeakHashMap<Any, PreviewBinding>())

    private fun prepare(context: Context) {
        if (environment != null) return
        synchronized(this) {
            if (environment == null) environment = IconEnvironment(
                context.applicationContext ?: context,
                invalidate = {
                    iconCache.clear()
                    bigFolderViewCache.clear()
                    ThemedIconPolicy.invalidateTheme()
                },
                refresh = ::refreshVisibleIcons,
                trim = { iconCache.clear() }
            )
        }
    }

    private fun binding(
        map: MutableMap<View, Binding>, view: View, item: Any, drawable: Drawable
    ): Binding? {
        val key = ThemedIconPolicy.itemKey(item)
        if (ThemedIconPolicy.isKnownThemedDrawable(drawable)) return map[view]?.takeIf { it.key == key }
        return originalBinding(key, drawable).also { map[view] = it }
    }

    private fun originalBinding(key: String, drawable: Drawable): Binding {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
            ?: (CachedReflection.call(drawable, "getBitmap") as? Bitmap)
        return Binding(key, drawable.constantState, WeakReference(drawable),
            bitmap?.generationId ?: drawable.constantState?.hashCode())
    }

    private fun refreshVisibleIcons() {
        val views = synchronized(bubbles) { bubbles.entries.map { it.key to it.value } }
        for ((view, source) in views) {
            val item = ThemedIconPolicy.itemForView(view) ?: readField(view, "mInfo") ?: continue
            if (!view.isAttachedToWindow || ThemedIconPolicy.itemKey(item) != source.key) continue
            val icon = invokeNoArg(view, "getIcon") as? Drawable ?: continue
            runCatching {
                CachedReflection.method(view.javaClass, "setIcon", Drawable::class.java)?.invoke(view, icon)
            }.onFailure { moduleLog("Cannot refresh themed home icon", it) }
        }
        val items = synchronized(folders) { folders.keys.toList() }
        for (view in items) if (view.isAttachedToWindow) reconcileBigFolderItem(view)
        val entries = synchronized(previews) { previews.entries.map { it.key to it.value } }
        for ((params, entry) in entries) {
            val owner = entry.owner.get() ?: continue
            afterPreviewSetDrawable(owner, arrayOf(params, entry.item))
            val folder = (invokeNoArg(owner, "getFolderIcon") ?: readField(owner, "mIcon")) as? View
            folder?.invalidate()
        }
    }

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

        val context = view.context.applicationContext ?: view.context
        prepare(context)
        val original = binding(bubbles, view, itemInfo, drawable)
        // Skip already themed drawables to avoid repeated monochrome conversion.
        if (ThemedIconPolicy.isThemedDrawable(drawable)) {
            ThemedIconPolicy.rememberDrawable(drawable, itemInfo)
            return
        }

        val themed = themedDrawable(context, pkg, drawable, itemInfo, original) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        args[0] = themed

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

        val context = view.context.applicationContext ?: view.context
        prepare(context)
        val originalBinding = binding(folders, view, info, source)
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

        val themed = themedDrawable(context, pkg, source, info, originalBinding) ?: return null
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
        val context = resolveContext(owner) ?: return
        prepare(context)
        val key = ThemedIconPolicy.itemKey(itemInfo)
        val original = if (ThemedIconPolicy.isKnownThemedDrawable(drawable)) {
            previews[previewParams]?.source?.takeIf { it.key == key }
        } else originalBinding(key, drawable)
        if (owner != null) previews[previewParams] = PreviewBinding(WeakReference(owner), itemInfo, original)
        if (ThemedIconPolicy.isThemedDrawable(drawable)) {
            ThemedIconPolicy.rememberDrawable(drawable, itemInfo)
            return
        }
        val themed = themedDrawable(context, pkg, drawable, itemInfo, original) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        writeField(previewParams, "drawable", themed)

    }

    private fun beforeFloatingSetIcon(owner: Any?, args: Array<Any?>) {
        val view = owner as? View ?: return
        val drawable = args.firstOrNull() as? Drawable ?: return
        val itemInfo = resolveFloatingItemInfo(owner) ?: return
        val pkg = resolvePackageName(itemInfo) ?: return
        val originalView = readField(owner, "mOriginalIcon") as? View
        if (!shouldApplyToPackage(pkg) ||
            !ThemedIconPolicy.shouldThemeSurfaceTarget(originalView, itemInfo)) return

        val context = view.context.applicationContext ?: view.context
        prepare(context)
        val themed = ThemedIconPolicy.themedStateFor(itemInfo)
            ?.newDrawable(context.resources)?.mutate()
            ?: themedDrawable(context, pkg, drawable, itemInfo) ?: return
        ThemedIconPolicy.rememberDrawable(themed, itemInfo)
        themed.bounds = drawable.bounds
        args[0] = themed

        // Keep OOS' load result coherent with the drawable passed to ClipIconView.
        val loadResult = readField(owner, "mIconLoadResult")
        if (loadResult != null) {
            runCatching { writeField(loadResult, "drawable", themed) }
            runCatching { writeField(loadResult, "isThemed", true) }
        }

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

    private fun findField(owner: Any, name: String): Field? =
        CachedReflection.field(owner.javaClass, name)

    private fun readField(owner: Any?, name: String): Any? {
        if (owner == null) return null
        return runCatching { findField(owner, name)?.get(owner) }.getOrNull()
    }

    private fun writeField(owner: Any, name: String, value: Any?) {
        val field = findField(owner, name) ?: return
        field.set(owner, value)
    }

    private fun findNoArgMethod(owner: Any, name: String): Method? =
        CachedReflection.method(owner.javaClass, name)

    private fun invokeNoArg(owner: Any, name: String): Any? {
        return runCatching { findNoArgMethod(owner, name)?.invoke(owner) }.getOrNull()
    }

    private fun resolveContext(owner: Any?): Context? = CachedReflection.context(owner)

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
            // Read the manifest resource directly. OEM LauncherActivityInfo#getIcon
            // may return a pre-scaled/themed adaptive wrapper instead of the APK icon.
            val manifest = context.packageManager.getActivityInfo(
                component, PackageManager.ComponentInfoFlags.of(0)
            )
            val res = context.packageManager.getResourcesForApplication(
                activityInfo?.applicationInfo ?: manifest.applicationInfo
            )
            val raw = res.getDrawableForDensity(
                manifest.iconResource, context.resources.displayMetrics.densityDpi, null
            ) ?: return@runCatching null
            raw
        }.getOrElse { error ->
            moduleLog("raw resource unavailable pkg=${component.packageName}: ${error.javaClass.simpleName}")
            runCatching { context.packageManager.getActivityIcon(component) }.getOrNull()
        }
    }

    private fun resolveBestSourceDrawable(
        context: Context,
        itemInfo: Any,
        fallback: Drawable
    ): Drawable {
        val itemType = resolveItemType(itemInfo)
        if (isShortcutLikeItem(itemInfo)) {
            val id = (invokeNoArg(itemInfo, "getDeepShortcutId") as? String)
                ?: (readField(itemInfo, "deepShortcutId") as? String)
            val pkg = resolvePackageName(itemInfo)
            if (!id.isNullOrEmpty() && pkg != null) {
                val raw = runCatching {
                    val apps = context.getSystemService(LauncherApps::class.java)
                    val query = LauncherApps.ShortcutQuery()
                        .setPackage(pkg).setShortcutIds(listOf(id))
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                    val user = resolveUserHandle(itemInfo) ?: android.os.Process.myUserHandle()
                    val info = apps.getShortcuts(query, user)?.firstOrNull()
                    info?.let { apps.getShortcutIconDrawable(it,
                        context.resources.displayMetrics.densityDpi) }
                }.getOrNull()
                if (raw != null) return raw
            }
            return fallback
        }
        if (itemType != 0) return fallback
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
        context: Context, pkg: String, fallback: Drawable, item: Any,
        originalBinding: Binding? = null
    ): Drawable? {
        val component = resolveComponentName(item)
        val user = resolveUserHandle(item) ?: android.os.Process.myUserHandle()
        val shortcutId = (invokeNoArg(item, "getDeepShortcutId") as? String)
            ?: (readField(item, "deepShortcutId") as? String)
        val key = when {
            resolveItemType(item) == 0 && component != null -> "app|$user|$component"
            !shortcutId.isNullOrEmpty() -> "shortcut|$user|$pkg|$shortcutId"
            else -> {
                val version = originalBinding?.version ?: this.originalBinding("", fallback).version
                "${ThemedIconPolicy.itemKey(item)}|$version"
            }
        }
        val bitmap = iconCache.getOrLoad(key) {
            val unthemed = if (ThemedIconPolicy.isKnownThemedDrawable(fallback)) {
                originalBinding?.restore(context) ?: fallback
            } else fallback
            val source = resolveBestSourceDrawable(context, item, unthemed)
            // If the original source has disappeared, retain the launcher's
            // drawable instead of converting an old themed bitmap a second time.
            if (ThemedIconPolicy.isKnownThemedDrawable(source)) null else {
                generateRawThemedBitmap(context, pkg, source,
                    isShortcutLikeItem(item), resolveComponentName(item))
            }
        } ?: return null
        return bitmap.toDrawable(context.resources)
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

        val monoDrawable: Drawable = when {
            legacyBitmap != null -> BitmapDrawable(context.resources, legacyBitmap).mutate()
            pixelBitmap != null -> BitmapDrawable(context.resources, pixelBitmap).mutate()
            else -> {
                val alpha = runCatching {
                    Pixel17MonochromeRenderer.render(
                        original, 192, context.resources, matchThemeBackground = true
                    )
                }.recoverCatching {
                    val software = extractSoftwareBitmap(original) ?: throw it
                    Pixel17MonochromeRenderer.render(
                        BitmapDrawable(context.resources, software), 192, context.resources,
                        matchThemeBackground = true
                    )
                }.onFailure {
                    moduleLog("skip Pixel mono generation pkg=$pkg: ${it.message}")
                }.getOrNull() ?: return null
                BitmapDrawable(context.resources, alpha)
            }
        }

        val size = 192
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val radius = size * 0.5f
        canvas.drawCircle(size * 0.5f, size * 0.5f, radius, bgPaint)

        monoDrawable.setTint(iconColor)
        monoDrawable.setTintMode(PorterDuff.Mode.SRC_IN)

        val iconSize = when {
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
            // The Pixel renderer already applies the negative inset to both
            // native and synthetic layers. Do not apply a second expansion.
            else -> size
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
                moduleLog("skip mono draw: ${it.message}")
                return null
            }
        }

        return circleMasked(bitmap)
    }

}
