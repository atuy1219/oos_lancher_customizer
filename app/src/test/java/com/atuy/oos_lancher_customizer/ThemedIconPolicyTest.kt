package com.atuy.oos_lancher_customizer

import org.junit.Assert.*
import org.junit.Test

class ThemedIconPolicyTest {
    private class Item(
        val id: Long = 1,
        val itemType: Int = 0,
        val container: Int = -100,
        val user: String = "personal",
        private val width: Int = 1,
        private val height: Int = 1,
        private val shortcut: String? = null
    ) {
        fun getSpanX() = width
        fun getSpanY() = height
        fun getDeepShortcutId() = shortcut
    }

    @Test fun normalAppsAndHomeShortcutsCanBeThemed() {
        for (type in listOf(0, 1, 6)) {
            assertTrue(ThemedIconPolicy.shouldThemeItem(Item(itemType = type)))
        }
    }

    @Test fun stretchedIconsKeepTheLauncherArtwork() {
        for ((width, height) in listOf(2 to 1, 1 to 2, 2 to 2)) {
            assertFalse(ThemedIconPolicy.shouldThemeItem(Item(width = width, height = height)))
            assertFalse(ThemedIconPolicy.shouldThemeItem(
                Item(itemType = 6, width = width, height = height)
            ))
        }
    }

    @Test fun categoryAndDrawerItemsKeepTheLauncherArtwork() {
        assertFalse(ThemedIconPolicy.shouldThemeItem(Item(container = -201)))
        assertFalse(ThemedIconPolicy.shouldThemeItem(Item(container = -104)))
        assertFalse(ThemedIconPolicy.shouldThemeItem(Item(itemType = 2)))
        assertFalse(ThemedIconPolicy.shouldThemeItem(null))
    }

    @Test fun normalAppAndShortcutsNeverShareAnAnimationCacheKey() {
        val app = ThemedIconPolicy.itemKey(Item())
        val shortcut = ThemedIconPolicy.itemKey(Item(itemType = 6, shortcut = "chat"))
        assertNotEquals(app, shortcut)
        assertNotEquals(shortcut,
            ThemedIconPolicy.itemKey(Item(itemType = 6, shortcut = "camera")))
    }

    @Test fun profilesAndSeparateHomeItemsNeverShareAnAnimationCacheKey() {
        val original = ThemedIconPolicy.itemKey(Item())
        assertNotEquals(original, ThemedIconPolicy.itemKey(Item(user = "work")))
        assertNotEquals(original, ThemedIconPolicy.itemKey(Item(id = 2)))
        assertEquals(original, ThemedIconPolicy.itemKey(Item()))
    }
}
