package com.atuy.oos_lancher_customizer

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log

/** One observer set for the launcher process; bursts share one UI refresh. */
internal class IconEnvironment(
    private val context: Context,
    private val invalidate: () -> Unit,
    private val refresh: () -> Unit,
    private val trim: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private data class Appearance(val config: Configuration, val background: Int, val foreground: Int)
    private fun appearance(): Appearance {
        val config = context.resources.configuration
        val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return Appearance(Configuration(config),
            context.getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100),
            context.getColor(if (dark) android.R.color.system_accent1_100 else android.R.color.system_accent1_600))
    }
    private var current = appearance()

    private fun changed(force: Boolean = false) {
        val next = appearance()
        if (!force && next == current) return
        current = next
        invalidate()
        if (!scheduled) {
            scheduled = true
            handler.post { scheduled = false; refresh() }
        }
    }

    private val configuration = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) { changed() }
        override fun onLowMemory() { trim() }
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim()
        }
    }
    private val wallpaper = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changed() }
    }
    private val packages = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) { changed(true) }
        override fun onPackageAdded(packageName: String, user: UserHandle) { changed(true) }
        override fun onPackageChanged(packageName: String, user: UserHandle) { changed(true) }
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { changed(true) }
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { changed(true) }
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) { changed(true) }
    }

    init {
        context.registerComponentCallbacks(configuration)
        runCatching {
            context.registerReceiver(wallpaper, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w("OOS_IconEnvironment", "Wallpaper observer unavailable", it) }
        runCatching {
            context.getSystemService(LauncherApps::class.java)?.registerCallback(packages, handler)
        }.onFailure { Log.w("OOS_IconEnvironment", "Package observer unavailable", it) }
    }
}
