package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        Log.i("MeroLanguage", "Language mode loaded: ${mode.value.name}")
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

    fun promptDirective(latestUserText: String? = null): String = when (mode.value) {
        LanguageMode.AUTO -> when {
            latestUserText.isNullOrBlank() ->
                "Match the language and script of the latest user message only, " +
                    "regardless of earlier turns."
            DEVANAGARI.containsMatchIn(latestUserText) ->
                "The latest user message is in Devanagari Nepali. Reply in fluent, natural " +
                    "Devanagari Nepali regardless of the language of earlier turns."
            ROMANIZED_NEPALI.containsMatchIn(latestUserText) ->
                "The latest user message is Romanized Nepali. Reply in natural, conversational " +
                    "Romanized Nepali regardless of the language of earlier turns."
            else ->
                "The latest user message is English. Reply in clear, concise English " +
                    "regardless of the language of earlier turns."
        }
        LanguageMode.ENGLISH ->
            "Reply in clear, concise English."
        LanguageMode.NEPALI_DEVANAGARI ->
            "Always reply in fluent, natural, conversational Nepali using Devanagari script (नेपाली)."
        LanguageMode.NEPALI_ROMANIZED ->
            "Always reply in natural, conversational Romanized Nepali (Nepali written in Latin letters)."
    }

    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
    private val ROMANIZED_NEPALI = Regex(
        "\\b(mero|malai|tapai|timi|chha|cha|huncha|garna|kina|kasto|ramro|" +
            "dhanyabad|hola|pani|kasari|kaha|k\\s*ho)\\b",
        RegexOption.IGNORE_CASE,
    )
}
