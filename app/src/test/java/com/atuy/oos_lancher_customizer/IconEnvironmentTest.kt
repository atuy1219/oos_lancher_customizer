package com.atuy.oos_lancher_customizer

import android.app.Application
import android.content.Intent
import android.content.ComponentCallbacks2
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class IconEnvironmentTest {
    @Test fun configurationChangesInvalidateOnceAndRefreshOnTheMainThread() {
        val app: Application = RuntimeEnvironment.getApplication()
        var invalidations = 0
        var refreshes = 0
        var trims = 0
        IconEnvironment(app, { invalidations++ }, { refreshes++ }, { trims++ })
        app.onConfigurationChanged(app.resources.configuration)
        assertEquals(0, invalidations)
        RuntimeEnvironment.setQualifiers("+night")
        app.onConfigurationChanged(app.resources.configuration)
        assertEquals(1, invalidations)
        assertEquals(0, refreshes)
        app.onConfigurationChanged(app.resources.configuration)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, invalidations)
        assertEquals(1, refreshes)
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(0, trims)
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(1, trims)
        app.onLowMemory()
        assertEquals(2, trims)
    }

    @Test fun wallpaperBroadcastWithUnchangedPaletteDoesNotReloadIcons() {
        val app: Application = RuntimeEnvironment.getApplication()
        var changes = 0
        IconEnvironment(app, { changes++ }, { changes++ }, {})
        app.sendBroadcast(Intent(Intent.ACTION_WALLPAPER_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, changes)
    }
}
