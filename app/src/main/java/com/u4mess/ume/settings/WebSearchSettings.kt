package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences

/** Optional Brave Search key, encrypted with the same Android Keystore as LLM keys. */
object WebSearchSettings {

    private const val PREFS_NAME = "ume_web_search"
    private const val KEY_BRAVE = "brave_api_key"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun apiKey(): String? =
        if (::prefs.isInitialized) {
            SecureStore.load(prefs, KEY_BRAVE)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun hasKey(): Boolean = !apiKey().isNullOrBlank()

    fun saveApiKey(value: String) {
        SecureStore.save(prefs, KEY_BRAVE, value.trim())
    }
}
