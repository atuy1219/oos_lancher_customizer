package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OplusMaterialIconSourceTest {
    @Test
    fun onlyConfirmedOnePlusAndOplusAppsCanUseOldMaterialArtwork() {
        assertTrue(OplusMaterialIconSource.isEligiblePackage("com.oneplus.gallery"))
        assertTrue(OplusMaterialIconSource.isEligiblePackage("com.oneplus.filemanager"))
        assertTrue(OplusMaterialIconSource.isEligiblePackage("com.oplus.camera"))

        // Being preinstalled, or even an Android system application, is
        // not evidence of a ColorOS 15 OEM-specific Material icon.
        for (pkg in listOf(
            "com.android.chrome",
            "com.google.android.apps.maps",
            "com.google.android.apps.photos",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.oneplus.unknown",
            "com.oplus.unknown",
            ""
        )) {
            assertFalse(pkg, OplusMaterialIconSource.isEligiblePackage(pkg))
        }
    }
}
