package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

enum class ThemeMode(val label: String) {
    OLED("OLED"),
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System"),
    DYNAMIC("Dynamic"),
}

object ThemeSettings {

    private const val PREFS_NAME = "ume_theme"
    private const val KEY_MODE = "mode"

    private lateinit var prefs: SharedPreferences

    val mode = MutableStateFlow(ThemeMode.OLED)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mode.value = prefs.getString(KEY_MODE, null)
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            ?: ThemeMode.OLED
    }

    fun setMode(value: ThemeMode) {
        mode.value = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }
}
