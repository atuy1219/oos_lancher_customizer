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

    @Test fun onlyWorkspaceHotseatAndFolderItemsAreThemeable() {
        for (container in listOf(-100, -101, 42)) {
            assertTrue("container=$container", ThemedIconPolicy.shouldThemeItem(
                Item(container = container)
            ))
        }
        for (container in listOf(
            -1, -104, -105, -106, -107, -110, -111, -112, -113,
            -201, -225, -102, -103
        )) {
            assertFalse("container=$container", ThemedIconPolicy.shouldThemeItem(
                Item(container = container)
            ))
        }
    }

    @Test fun widgetsCardsAndNonAppItemsAreNeverThemed() {
        for (type in listOf(-1, 3, 5, 7, 8, 99, 100, 102, 103, 105)) {
            assertFalse("type=$type", ThemedIconPolicy.shouldThemeItem(
                Item(itemType = type)
            ))
        }
    }

    @Test fun drawerCategoryAndWidgetDisplaysCannotBecomeAnimationTargets() {
        assertTrue(ThemedIconPolicy.isHomeDisplay(0))
        assertTrue(ThemedIconPolicy.isHomeDisplay(2))
        for (display in listOf(null, -1, 1, 5, 6, 7, 8, 9, 11, 12, 13)) {
            assertFalse("display=$display", ThemedIconPolicy.isHomeDisplay(display))
        }
    }

    @Test fun nonHomeSurfaceAncestryIsExcludedWithoutBlockingTheWorkspace() {
        for (name in listOf(
            "com.android.launcher3.allapps.AllAppsRecyclerView",
            "com.android.launcher3.allapps.OplusCategoryContainer",
            "com.android.launcher3.drawer.CategoryFolderIcon",
            "com.android.launcher3.widget.LauncherAppWidgetHostView",
            "com.android.launcher3.widget.WidgetsBottomSheet",
            "com.android.launcher3.search.SearchResultView",
            "com.android.launcher3.taskbar.TaskbarView"
        )) {
            assertTrue(name, ThemedIconPolicy.isExcludedSurfaceClass(name))
        }
        for (name in listOf(
            "com.android.launcher3.Workspace",
            "com.android.launcher3.folder.FlexibleFolderIcon",
            "com.android.launcher3.ShortcutAndWidgetContainer",
            "com.android.launcher3.OplusShortcutAndWidgetContainer"
        )) {
            assertFalse(name, ThemedIconPolicy.isExcludedSurfaceClass(name))
        }
    }
}
