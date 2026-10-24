package com.atuy.oos_lancher_customizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class Oos15MonochromeAssetsTest {
    @Test fun assetBundleContainsAll39OriginalPackagesWithValidPngs() {
        val file = File("src/main/assets/oos15_monochrome.zip")
        assertTrue("Missing OEM source archive: $file", file.isFile)
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            assertEquals(118, entries.size)
            val normal = entries.filter { it.name.endsWith("/monochrome.png") }
            assertEquals(115, normal.size)
            assertEquals(39, normal.map { it.name.split('/')[1] }.toSet().size)
            for (entry in entries) {
                val png = zip.getInputStream(entry).use { it.readNBytes(8) }
                assertTrue(entry.name, png.contentEquals(byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                )))
            }
            assertTrue(zip.getEntry("xxxhdpi/com.oneplus.gallery/monochrome.png") != null)
            assertTrue(zip.getEntry("xxxhdpi/com.oneplus.filemanager/monochrome.png") != null)
            assertTrue(zip.getEntry("xxxhdpi/com.oplus.camera/monochrome.png") != null)
            assertTrue(zip.getEntry("xxxhdpi/com.android.contacts/dialer_monochrome.png") != null)
        }
    }
    @Test fun distinguishDialerFromContactsComponent() {
        assertTrue(Oos15MonochromeAssets.isDialerActivity(
            android.content.ComponentName("com.android.contacts", "com.android.contacts.activities.DialtactsActivity")
        ))
        assertFalse(Oos15MonochromeAssets.isDialerActivity(
            android.content.ComponentName("com.android.contacts", "com.android.contacts.activities.PeopleActivity")
        ))
    }
}
