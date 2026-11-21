package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

enum class LanguageMode(val label: String) {
    AUTO("Auto"),
    ENGLISH("English"),
    NEPALI_DEVANAGARI("नेपाली"),
    NEPALI_ROMANIZED("Romanized"),
}

object LanguageSettings {

    private const val PREFS_NAME = "ume_language"
    private const val KEY_MODE = "mode"

    private lateinit var prefs: SharedPreferences

    val mode = MutableStateFlow(LanguageMode.AUTO)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mode.value = prefs.getString(KEY_MODE, null)
            ?.let { saved -> LanguageMode.entries.firstOrNull { it.name == saved } }
            ?: LanguageMode.AUTO
    }

    fun setMode(value: LanguageMode) {
        mode.value = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }

    fun speechLanguage(): String? = when (mode.value) {
        LanguageMode.AUTO -> null
        LanguageMode.ENGLISH -> "en-US"
        LanguageMode.NEPALI_DEVANAGARI,
        LanguageMode.NEPALI_ROMANIZED,
        -> "ne-NP"
    }

    fun promptDirective(): String = when (mode.value) {
        LanguageMode.AUTO ->
            "Reply in the same language and script the user uses (English, Devanagari Nepali, " +
                "or Romanized Nepali). When replying in Nepali, use natural, conversational Nepali."
        LanguageMode.ENGLISH ->
            "Reply in clear, concise English."
        LanguageMode.NEPALI_DEVANAGARI ->
            "Always reply in fluent, natural, conversational Nepali using Devanagari script (नेपाली)."
        LanguageMode.NEPALI_ROMANIZED ->
            "Always reply in natural, conversational Romanized Nepali (Nepali written in Latin letters)."
    }
}
