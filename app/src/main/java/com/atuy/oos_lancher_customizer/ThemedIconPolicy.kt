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
 * Verified against OplusLauncher 16.6.17 and System Launcher 17.3.12 DEX constants.
 */
internal object ThemedIconPolicy {
    private const val DISPLAY_WORKSPACE = 0
    private const val DISPLAY_FOLDER = 2
    private const val CONTAINER_DESKTOP = -100
    private const val CONTAINER_HOTSEAT = -101
    private const val BIG_FOLDER_ITEM_VIEW =
        "com.android.launcher3.folder.big.BigFolderItemIcon"

    private val themedDrawables = Collections.synchronizedMap(WeakHashMap<Drawable, Boolean>())

    fun rememberDrawable(drawable: Drawable) {
        themedDrawables[drawable] = true
    }

    fun isThemedDrawable(drawable: Drawable): Boolean = themedDrawables[drawable] == true

    // Positive containers identify folder IDs; other negative containers belong to
    // special launcher surfaces (all apps, drawer categories, widgets, search, etc.).
    internal fun isHomeContainer(container: Int?): Boolean =
        container == CONTAINER_DESKTOP || container == CONTAINER_HOTSEAT ||
            (container != null && container > 0)

    internal fun isHomeDisplay(display: Int?): Boolean =
        display == DISPLAY_WORKSPACE || display == DISPLAY_FOLDER

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

    private fun hasExcludedAncestor(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (isExcludedSurfaceClass(current.javaClass.name)) return true
            current = current.parent as? View
        }
        return false
    }

    fun shouldThemeView(view: View?, item: Any?): Boolean {
        if (view == null || !shouldThemeItem(item)) return false
        val display = (read(view, "mDisplay") as? Number)?.toInt()
        if (display == null) {
            if (view.javaClass.name != BIG_FOLDER_ITEM_VIEW) return false
            // BigFolderItemIcon can live outside the ordinary View parent hierarchy.
            val parentFolder = call(view, "getParentFolderIcon") as? View ?: return false
            if (hasExcludedAncestor(parentFolder)) return false
            val parentInfo = call(parentFolder, "getInfo") ?: parentFolder.tag
            if (!isHomeContainer((parentInfo?.let { read(it, "container") } as? Number)?.toInt())) {
                return false
            }
        } else if (!isHomeDisplay(display)) {
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

    // Package names alone collide between activities, profiles and deep shortcuts.
    fun itemKey(item: Any): String {
        val component = (call(item, "getTargetComponent") as? ComponentName)
            ?: (read(item, "componentName") as? ComponentName)
            ?: (call(item, "getMTargetComponent") as? ComponentName)
        val intent = (call(item, "getIntent") as? Intent)
            ?: (read(item, "intent") as? Intent)
        return listOf(
            read(item, "id"), read(item, "user"), read(item, "itemType"),
            component?.flattenToString(), call(item, "getDeepShortcutId"),
            intent?.toUri(0)
        ).joinToString("|")
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
