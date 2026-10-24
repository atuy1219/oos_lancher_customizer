package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Scope themed icons to real workspace/hotseat icons and icons inside home-screen folders.
 *
 * Oplus uses the same item and floating-icon machinery for the app drawer, category pages,
 * search results and widget-driven launches. A package name or an app-like ItemInfo is not
 * sufficient to decide whether a return animation may use a themed icon.
 *
 * Verified against OplusLauncher 16.6.20 and System Launcher 17.3.12 DEX constants.
 */
internal object ThemedIconPolicy {
    private const val DISPLAY_WORKSPACE = 0
    private const val DISPLAY_FOLDER = 2
    private const val CONTAINER_DESKTOP = -100
    private const val CONTAINER_HOTSEAT = -101
    private const val CONTAINER_HOTSEAT_PREDICTION = -103
    private const val BIG_FOLDER_ITEM_VIEW =
        "com.android.launcher3.folder.big.BigFolderItemIcon"

    private val themedDrawables = Collections.synchronizedMap(WeakHashMap<Drawable, Boolean>())

    // Shared between ThemedIcons, FloatingIconGuard and IconSurfaceGuard.
    // Key by the complete launcher item identity, never the package name alone.
    // Bounded to avoid keeping an entire app drawer worth of BitmapDrawable states.
    private val themedStates = Collections.synchronizedMap(
        object : LinkedHashMap<String, Drawable.ConstantState>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Drawable.ConstantState>?
            ): Boolean = size > 128
        }
    )

    fun rememberDrawable(drawable: Drawable, item: Any? = null) {
        themedDrawables[drawable] = true
        if (item != null && shouldThemeItem(item)) {
            drawable.constantState?.let { state ->
                // Folder preview rendering changes alpha on its Drawable.
                // Cache a *detached* ConstantState so a dimmed frame cannot
                // overwrite the icon used by the app-close animation.
                val cleanState = runCatching {
                    state.newDrawable().mutate().apply {
                        alpha = 255
                        clearColorFilter()
                    }.constantState
                }.getOrNull() ?: state
                synchronized(themedStates) { themedStates[itemKey(item)] = cleanState }
            }
        }
    }

    fun themedStateFor(item: Any): Drawable.ConstantState? =
        synchronized(themedStates) { themedStates[itemKey(item)] }

    fun isThemedDrawable(drawable: Drawable): Boolean = themedDrawables[drawable] == true

    // Positive containers identify folder IDs. -103 is Oplus' predicted Hotseat,
    // unlike -102 (the prediction surface outside the dock).
    internal fun isHotseatContainer(container: Int?): Boolean =
        container == CONTAINER_HOTSEAT || container == CONTAINER_HOTSEAT_PREDICTION

    internal fun isHomeContainer(container: Int?): Boolean =
        container == CONTAINER_DESKTOP || isHotseatContainer(container) ||
            (container != null && container > 0)

    internal fun isHomeDisplay(display: Int?): Boolean =
        display == DISPLAY_WORKSPACE || display == DISPLAY_FOLDER

    // Oplus dock icons may use non-workspace BubbleTextView display modes.
    // In that case, the actual Hotseat ancestry is more reliable than mDisplay.
    internal fun isEligibleDisplay(display: Int?, container: Int?, onHotseat: Boolean): Boolean {
        if (isHotseatContainer(container) && onHotseat && display != null) return true
        if (container == CONTAINER_HOTSEAT_PREDICTION) return false
        return isHomeDisplay(display)
    }

    fun shouldThemeItem(item: Any?): Boolean {
        if (item == null) return false
        val type = (read(item, "itemType") as? Number)?.toInt()
        if (type != 0 && type != 1 && type != 6) return false
        val container = (read(item, "container") as? Number)?.toInt()
        if (!isHomeContainer(container)) return false
        val spanX = (call(item, "getSpanX") as? Number)?.toInt()
            ?: (read(item, "spanX") as? Number)?.toInt() ?: 1
        val spanY = (call(item, "getSpanY") as? Number)?.toInt()
            ?: (read(item, "spanY") as? Number)?.toInt() ?: 1
        return spanX == 1 && spanY == 1
    }

    fun itemForView(view: View): Any? = call(view, "getItemInfo") ?: view.tag

    /**
     * Exclude launcher-owned UI surfaces before looking at the item. In particular,
     * a widget may launch an app whose ItemInfo looks identical to a desktop icon.
     */
    internal fun isExcludedSurfaceClass(className: String): Boolean {
        val name = className.lowercase(Locale.ROOT)
        val simpleName = name.substringAfterLast('.')
        // ShortcutAndWidgetContainer is the normal CellLayout child on the desktop.
        if (simpleName.contains("widget") &&
            !simpleName.endsWith("shortcutandwidgetcontainer")) return true
        return listOf(
            "allapps", "drawer", "category", "cluster", "searchresult",
            "taskbar", "prediction", "recommend", "smartsidebar", "cardcontainer"
        ).any { name.contains(it) }
    }

    // Check the real View hierarchy, not a class-name substring of the icon itself.
    private fun hasHotseatAncestor(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            var cls: Class<*>? = current.javaClass
            while (cls != null) {
                if (cls.name == "com.android.launcher3.Hotseat" ||
                    cls.name == "com.android.launcher3.OplusHotseat") return true
                cls = cls.superclass
            }
            current = current.parent as? View
        }
        return false
    }

    private fun hasExcludedAncestor(view: View): Boolean {
        val onHotseat = hasHotseatAncestor(view)
        var current: View? = view
        while (current != null) {
            val className = current.javaClass.name
            val hotseatPrediction = onHotseat &&
                (className.contains("hotseatprediction", ignoreCase = true) ||
                    className.contains("hybridhotseat", ignoreCase = true))
            if (!hotseatPrediction && isExcludedSurfaceClass(className)) return true
            current = current.parent as? View
        }
        return false
    }

    fun shouldThemeView(view: View?, item: Any?): Boolean {
        if (view == null || !shouldThemeItem(item)) return false
        val display = (read(view, "mDisplay") as? Number)?.toInt()
        val container = (read(item!!, "container") as? Number)?.toInt()
        if (display == null) {
            if (view.javaClass.name != BIG_FOLDER_ITEM_VIEW) return false
            // BigFolderItemIcon can live outside the ordinary View parent hierarchy.
            val parentFolder = call(view, "getParentFolderIcon") as? View ?: return false
            if (hasExcludedAncestor(parentFolder)) return false
            val parentInfo = call(parentFolder, "getInfo") ?: parentFolder.tag
            if (!isHomeContainer((parentInfo?.let { read(it, "container") } as? Number)?.toInt())) {
                return false
            }
        } else if (!isEligibleDisplay(display, container, hasHotseatAncestor(view))) {
            return false
        }
        return !hasExcludedAncestor(view)
    }

    /**
     * PreviewItemManager#setDrawable has no BubbleTextView display flag. Use its actual
     * folder icon, not only the child's ItemInfo, to reject drawer-category previews.
     */
    fun shouldThemeFolderPreview(owner: Any?, item: Any?): Boolean {
        if (!shouldThemeItem(item) || owner == null) return false
        val folderIcon = (call(owner, "getFolderIcon") ?: read(owner, "mIcon")) as? View
            ?: return false
        if (hasExcludedAncestor(folderIcon)) return false
        val folderInfo = call(folderIcon, "getInfo") ?: folderIcon.tag ?: return false
        return isHomeContainer((read(folderInfo, "container") as? Number)?.toInt())
    }

    fun shouldThemeAnimation(view: View?, item: Any?): Boolean {
        if (!shouldThemeView(view, item)) return false
        val target = itemForView(view!!) ?: return false
        return shouldThemeItem(target) && itemKey(target) == itemKey(item!!)
    }

    /**
     * IconSurface may use FolderIcon as the return target of an app inside a
     * home folder. The view's tag is FolderInfo, not the app's ItemInfo.
     * Accept that indirection only when the folder's database ID equals the app's
     * positive container ID and the folder itself belongs to the home workspace.
     */
    internal fun isMatchingHomeFolder(
        itemContainer: Long?, folderId: Long?, folderContainer: Int?
    ): Boolean =
        itemContainer != null && itemContainer > 0L && itemContainer == folderId &&
            isHomeContainer(folderContainer)

    fun shouldThemeSurfaceTarget(view: View?, item: Any?): Boolean {
        if (shouldThemeAnimation(view, item)) return true
        if (view == null || !shouldThemeItem(item)) return false
        if (hasExcludedAncestor(view)) return false
        if (!view.javaClass.name.contains("FolderIcon")) return false
        val childContainer = (read(item!!, "container") as? Number)?.toLong() ?: return false
        val folderInfo = call(view, "getInfo") ?: read(view, "mInfo") ?: view.tag ?: return false
        val folderId = (read(folderInfo, "id") as? Number)?.toLong()
        val parentContainer = (read(folderInfo, "container") as? Number)?.toInt()
        return isMatchingHomeFolder(childContainer, folderId, parentContainer)
    }

    // Launcher mutates Intent flags/extras/sourceBounds on each launch, including
    // anim_from_item_click and allow_pre_start. Never include Intent#toUri() in the
    // cache key or the same home icon becomes a cache MISS during app close.
    //
    // A persisted item ID uniquely identifies a workspace item. Component,
    // profile and shortcut ID protect against recycled IDs and shortcut changes.
    internal fun stableItemKey(
        id: Any?,
        user: Any?,
        itemType: Any?,
        component: String?,
        shortcutId: String?
    ): String = listOf(id, user, itemType, component, shortcutId).joinToString("|")

    fun itemKey(item: Any): String {
        val intent = (call(item, "getIntent") as? Intent)
            ?: (read(item, "intent") as? Intent)
        val component = (call(item, "getTargetComponent") as? ComponentName)
            ?: (read(item, "componentName") as? ComponentName)
            ?: (call(item, "getMTargetComponent") as? ComponentName)
            ?: intent?.component
        return stableItemKey(
            read(item, "id"), read(item, "user"), read(item, "itemType"),
            component?.flattenToString(),
            (call(item, "getDeepShortcutId") as? String)
                ?: (read(item, "deepShortcutId") as? String)
        )
    }

    private fun read(owner: Any, name: String): Any? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) return runCatching {
                field.isAccessible = true
                field.get(owner)
            }.getOrNull()
            cls = current.superclass
        }
        return null
    }

    private fun call(owner: Any, name: String): Any? {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            val current = cls
            val method = current.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) return runCatching {
                method.isAccessible = true
                method.invoke(owner)
            }.getOrNull()
            cls = current.superclass
        }
        return null
    }
}
