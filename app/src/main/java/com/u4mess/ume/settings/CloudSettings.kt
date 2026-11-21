package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

enum class CloudProvider(
    val id: String,
    val label: String,
    val endpoint: String,
    val defaultModel: String,
) {
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1/chat/completions",
        defaultModel = "deepseek-chat",
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        defaultModel = "deepseek/deepseek-chat",
    ),
    OPENAI(
        id = "openai",
        label = "OpenAI",
        endpoint = "https://api.openai.com/v1/chat/completions",
        defaultModel = "gpt-4o-mini",
    ),
    CUSTOM(
        id = "custom",
        label = "Custom",
        endpoint = "",
        defaultModel = "",
    ),
}

object CloudSettings {

    private const val PREFS_NAME = "ume_cloud"
    private const val KEY_ENABLED = "hybrid_actions"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
    private const val LEGACY_DEEPSEEK_KEY = "deepseek_api_key"

    private lateinit var prefs: SharedPreferences
    private val keyCache = mutableMapOf<String, String?>()

    val enabled = MutableStateFlow(false)
    val provider = MutableStateFlow(CloudProvider.DEEPSEEK)
    val model = MutableStateFlow(CloudProvider.DEEPSEEK.defaultModel)
    val baseUrl = MutableStateFlow("")

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        provider.value = CloudProvider.entries
            .firstOrNull { it.id == prefs.getString(KEY_PROVIDER, null) }
            ?: CloudProvider.DEEPSEEK
        model.value = savedModel(provider.value)
        baseUrl.value = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty()
        migrateLegacyKey()
    }

    private fun migrateLegacyKey() {
        if (prefs.contains(apiKeyStorage(CloudProvider.DEEPSEEK))) return
        if (!prefs.contains(LEGACY_DEEPSEEK_KEY)) return

        SecureStore.load(prefs, LEGACY_DEEPSEEK_KEY)?.let { legacyKey ->
            SecureStore.save(prefs, apiKeyStorage(CloudProvider.DEEPSEEK), legacyKey)
        }
        prefs.edit().remove(LEGACY_DEEPSEEK_KEY).apply()
    }

    fun apiKey(): String? {
        val current = provider.value
        return keyCache.getOrPut(current.id) {
            SecureStore.load(prefs, apiKeyStorage(current))?.takeIf { it.isNotBlank() }
        }
    }

    fun hasKey(): Boolean = !apiKey().isNullOrBlank()

    fun saveApiKey(value: String) {
        val current = provider.value
        val trimmed = value.trim()
        SecureStore.save(prefs, apiKeyStorage(current), trimmed)
        keyCache[current.id] = trimmed.ifBlank { null }
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun selectProvider(value: CloudProvider) {
        if (provider.value == value) return
        provider.value = value
        prefs.edit().putString(KEY_PROVIDER, value.id).apply()
        model.value = savedModel(value)
        baseUrl.value = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty()
    }

    fun setModel(value: String) {
        model.value = value
        prefs.edit().putString(modelKey(provider.value), value).apply()
    }

    fun setBaseUrl(value: String) {
        baseUrl.value = value
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, value).apply()
    }

    fun endpoint(): String {
        val current = provider.value
        if (current != CloudProvider.CUSTOM) return current.endpoint

        val base = baseUrl.value.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun modelId(): String = model.value.trim()

    fun providerLabel(): String = provider.value.label

    private fun savedModel(value: CloudProvider): String =
        prefs.getString(modelKey(value), null)?.takeIf { it.isNotBlank() } ?: value.defaultModel

    private fun modelKey(value: CloudProvider): String = "model_${value.id}"

    private fun apiKeyStorage(value: CloudProvider): String = "api_key_${value.id}"
}
