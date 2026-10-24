package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelLauncherThemedAssetsTest {
    @Test
    fun allElevenPixel17EntriesHaveExactPackageMatches() {
        for (pkg in listOf(
            "com.google.android.apps.classroom",
            "com.google.android.apps.translate",
            "com.google.android.apps.recorder",
            "com.android.settings",
            "com.google.android.apps.nbu.paisa.user",
            "com.google.android.ims",
            "com.google.android.apps.fitness",
            "com.google.android.apps.podcasts",
            "com.google.android.apps.tips",
            "com.google.android.wearable.app",
            "com.nest.android"
        )) {
            assertTrue(pkg, PixelLauncherThemedAssets.hasArtwork(pkg))
        }
    }

    @Test
    fun thirdPartyApplicationsNeverAccidentallyMatchPixelIconNames() {
        for (pkg in listOf(
            "com.google.android.apps.classroom.other",
            "com.google.android.apps.maps",
            "com.android.chrome",
            "com.android.vending",
            "com.oneplus.gallery",
            "com.oplus.camera",
            ""
        )) {
            assertFalse(pkg, PixelLauncherThemedAssets.hasArtwork(pkg))
        }
    }
}
