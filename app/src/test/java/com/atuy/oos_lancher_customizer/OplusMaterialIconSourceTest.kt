package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OplusMaterialIconSourceTest {
    @Test
    fun onlyPreinstalledSystemAppsAreCandidates() {
        assertTrue(OplusMaterialIconSource.isPreinstalledSystemApp(0x00000001))
        assertTrue(OplusMaterialIconSource.isPreinstalledSystemApp(0x00000080))
        assertTrue(OplusMaterialIconSource.isPreinstalledSystemApp(0x00000081))
        assertFalse(OplusMaterialIconSource.isPreinstalledSystemApp(0))
        assertFalse(OplusMaterialIconSource.isPreinstalledSystemApp(0x00000002))
    }
}
