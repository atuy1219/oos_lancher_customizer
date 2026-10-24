package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.InflaterInputStream

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
    fun pixelApkPathsAreLosslesslyInflatableAndContainElevenVectors() {
        val source = File("src/main/java/com/atuy/oos_lancher_customizer/PixelLauncherThemedAssets.kt")
            .readText()
        val encoded = source.substringAfter("private const val VECTOR_DATA = \"")
            .substringBefore("\"")
        val compressed = Base64.getDecoder().decode(encoded)
        val json = InflaterInputStream(ByteArrayInputStream(compressed)).bufferedReader()
            .use { it.readText() }
        for (name in listOf(
            "classroom", "g_translate", "recorder", "settings",
            "gpay", "play_services", "gfit_health", "podcasts",
            "pixeltips", "wear_os", "nest"
        )) {
            assertTrue(name, json.contains("\"$name\":"))
        }
        assertTrue("Expected original vector path data", json.contains("M30 7.5H16.32"))
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
