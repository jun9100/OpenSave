package com.jun9100.opensave

import android.content.Context

/** Display settings of 设置 → 显示. */
class DisplayPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Off by default (plan Q2): the bars show the battery and the time. */
    fun immersiveLandscape(): Boolean = preferences.getBoolean(IMMERSIVE_LANDSCAPE_KEY, false)

    fun setImmersiveLandscape(enabled: Boolean) {
        preferences.edit().putBoolean(IMMERSIVE_LANDSCAPE_KEY, enabled).apply()
    }

    private companion object {
        const val PREFERENCES = "opensave_display"
        const val IMMERSIVE_LANDSCAPE_KEY = "immersive_landscape"
    }
}
