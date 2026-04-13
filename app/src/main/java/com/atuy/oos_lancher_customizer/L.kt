package com.atuy.oos_lancher_customizer

import android.util.Log

object L {
    private const val TAG = "OOS16_ThemedIcons"
    private val ENABLED = BuildConfig.DEBUG

    fun d(msg: String) {
        if (ENABLED) Log.d(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (ENABLED) Log.e(TAG, msg, t)
    }
}


