package com.atuy.oos_lancher_customizer

import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.libxposed.service.XposedService

class MainActivity : AppCompatActivity(), ModuleApplication.ServiceStateListener {

    private lateinit var loopSwitch: SwitchMaterial
    private lateinit var statusView: TextView
    private var remotePrefs: SharedPreferences? = null
    private var remoteListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)

        val padding = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.discover_always_disabled)
            textSize = 16f
            setPadding(0, 0, 0, dp(20))
        })

        loopSwitch = SwitchMaterial(this).apply {
            text = getString(R.string.workspace_loop_title)
            isEnabled = false
        }
        content.addView(
            loopSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(TextView(this).apply {
            text = getString(R.string.workspace_loop_summary)
            setPadding(0, dp(4), 0, dp(20))
        })

        statusView = TextView(this).apply {
            text = getString(R.string.xposed_service_waiting)
        }
        content.addView(statusView)

        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onStart() {
        super.onStart()
        ModuleApplication.addServiceStateListener(this)
    }

    override fun onStop() {
        ModuleApplication.removeServiceStateListener(this)
        detachRemotePreferences()
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread { bindService(service) }
    }

    private fun bindService(service: XposedService?) {
        detachRemotePreferences()

        if (service == null) {
            loopSwitch.setOnCheckedChangeListener(null)
            loopSwitch.isEnabled = false
            statusView.text = getString(R.string.xposed_service_unavailable)
            return
        }

        val prefs = runCatching {
            service.getRemotePreferences(ModulePrefs.GROUP)
        }.getOrElse {
            loopSwitch.setOnCheckedChangeListener(null)
            loopSwitch.isEnabled = false
            statusView.text = getString(R.string.remote_preferences_unavailable)
            return
        }

        remotePrefs = prefs
        statusView.text = getString(R.string.xposed_service_connected)
        updateSwitchFrom(prefs)

        remoteListener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == ModulePrefs.KEY_WORKSPACE_LOOP) {
                runOnUiThread { updateSwitchFrom(changed) }
            }
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
    }

    private fun updateSwitchFrom(prefs: SharedPreferences) {
        loopSwitch.setOnCheckedChangeListener(null)
        loopSwitch.isChecked = prefs.getBoolean(ModulePrefs.KEY_WORKSPACE_LOOP, false)
        loopSwitch.isEnabled = true
        loopSwitch.setOnCheckedChangeListener { _, checked ->
            runCatching {
                prefs.edit().putBoolean(ModulePrefs.KEY_WORKSPACE_LOOP, checked).apply()
            }.onFailure {
                statusView.text = getString(R.string.preference_write_failed)
            }
        }
    }

    private fun detachRemotePreferences() {
        val prefs = remotePrefs
        val listener = remoteListener
        if (prefs != null && listener != null) {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        remotePrefs = null
        remoteListener = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
