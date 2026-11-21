package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

object CloudSettings {

    const val DEFAULT_MODEL = "deepseek-chat"
    val models = listOf(DEFAULT_MODEL, "deepseek-reasoner")

    private const val PREFS_NAME = "ume_cloud"
    private const val KEY_ENABLED = "hybrid_actions"
    private const val KEY_MODEL = "model"

    private lateinit var prefs: SharedPreferences
    private var apiKeyLoaded = false
    private var cachedApiKey: String? = null

    val enabled = MutableStateFlow(false)
    val model = MutableStateFlow(DEFAULT_MODEL)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        model.value = prefs.getString(KEY_MODEL, DEFAULT_MODEL)
            ?.takeIf { it in models }
            ?: DEFAULT_MODEL
    }

    /** Decrypts on first use so app startup does not touch the keystore unnecessarily. */
    fun apiKey(): String? {
        if (!apiKeyLoaded) {
            cachedApiKey = SecureStore.load(prefs)?.takeIf { it.isNotBlank() }
            apiKeyLoaded = true
        }
        return cachedApiKey
    }

    fun hasKey(): Boolean = !apiKey().isNullOrBlank()

    fun saveApiKey(value: String) {
        val trimmed = value.trim()
        SecureStore.save(prefs, trimmed)
        cachedApiKey = trimmed.ifBlank { null }
        apiKeyLoaded = true
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setModel(value: String) {
        if (value !in models) return
        model.value = value
        prefs.edit().putString(KEY_MODEL, value).apply()
    }
}
