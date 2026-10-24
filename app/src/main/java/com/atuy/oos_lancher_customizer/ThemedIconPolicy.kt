package com.atuy.oos_lancher_customizer

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

internal object ThemedIconPolicy {
    private val allowedDisplays = setOf(0, 2, 8, 9)
    private val themedDrawables = Collections.synchronizedMap(WeakHashMap<Drawable, Boolean>())

    fun rememberDrawable(drawable: Drawable) {
        themedDrawables[drawable] = true
    }

    fun isThemedDrawable(drawable: Drawable): Boolean = themedDrawables[drawable] == true

    fun shouldThemeItem(item: Any?): Boolean {
        if (item == null) return false
        val type = (read(item, "itemType") as? Number)?.toInt()
        if (type != 0 && type != 1 && type != 6) return false
        val container = (read(item, "container") as? Number)?.toInt()
        if (container == -104 || container == -201) return false
        val spanX = (call(item, "getSpanX") as? Number)?.toInt()
            ?: (read(item, "spanX") as? Number)?.toInt() ?: 1
        val spanY = (call(item, "getSpanY") as? Number)?.toInt()
            ?: (read(item, "spanY") as? Number)?.toInt() ?: 1
        return spanX == 1 && spanY == 1
    }

    fun itemForView(view: View): Any? =
        call(view, "getItemInfo") ?: view.tag

    fun shouldThemeView(view: View?, item: Any?): Boolean {
        if (view == null || !shouldThemeItem(item)) return false
        val display = (read(view, "mDisplay") as? Number)?.toInt()
        if (display != null && display !in allowedDisplays) return false
        // A category can also close into a folder preview rather than a BubbleTextView.
        if (display == null &&
            view.javaClass.name != "com.android.launcher3.folder.big.BigFolderItemIcon") {
            return false
        }
        var parent = if (display == null) {
            call(view, "getParentFolderIcon") ?: view.parent
        } else {
            view.parent
        }
        while (parent is View) {
            if (parent.javaClass.name.contains("allapps")) return false
            parent = parent.parent
        }
        return true
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
