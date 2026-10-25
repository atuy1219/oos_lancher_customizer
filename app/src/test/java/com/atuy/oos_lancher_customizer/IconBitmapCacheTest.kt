package com.atuy.oos_lancher_customizer

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IconBitmapCacheTest {
    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test fun hitsSkipTheEntireSourceLoaderAndRenderer() {
        val cache = IconBitmapCache()
        var sourceLoads = 0
        val expected = cache.getOrLoad("personal|app") { sourceLoads++; bitmap() }
        repeat(100) {
            assertSame(expected, cache.getOrLoad("personal|app") { sourceLoads++; bitmap() })
        }
        assertEquals(1, sourceLoads)
    }

    @Test fun invalidationReloadsSuccessfulAndFailedEntries() {
        val cache = IconBitmapCache()
        var loads = 0
        val old = cache.getOrLoad("app") { loads++; bitmap() }
        assertNull(cache.getOrLoad("missing") { loads++; null })
        assertNull(cache.getOrLoad("missing") { fail("failure must be cached"); bitmap() })
        cache.clear()
        assertNotSame(old, cache.getOrLoad("app") { loads++; bitmap() })
        assertNotNull(cache.getOrLoad("missing") { loads++; bitmap() })
        assertEquals(4, loads)
    }

    @Test fun lateResultCannotRepopulateAnInvalidatedCache() {
        val cache = IconBitmapCache()
        val obsolete = cache.getOrLoad("app") { cache.clear(); bitmap() }
        val fresh = cache.getOrLoad("app") { bitmap() }
        assertNotSame(obsolete, fresh)
        assertSame(fresh, cache.getOrLoad("app") { fail("fresh result must be cached"); null })
    }

    @Test fun bitmapMemoryBudgetEvictsLeastRecentlyUsedEntry() {
        val sample = bitmap()
        val cache = IconBitmapCache(sample.allocationByteCount)
        val first = cache.getOrLoad("one") { sample }
        cache.getOrLoad("two") { bitmap() }
        assertNotSame(first, cache.getOrLoad("one") { bitmap() })
    }
}
