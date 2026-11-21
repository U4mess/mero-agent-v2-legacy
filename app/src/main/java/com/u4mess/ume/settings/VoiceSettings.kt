package com.u4mess.ume.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

enum class VoiceEngine(val label: String) {
    SYSTEM("System TTS"),
    CLOUD_NEURAL("Cloud Neural"),
}

object VoiceSettings {

    private const val PREFS_NAME = "ume_voice_settings"
    private const val KEY_ENGINE = "voice_engine"
    private const val KEY_NEPALI_VOICE = "nepali_system_voice"
    private const val KEY_ENGLISH_VOICE = "english_system_voice"
    private const val KEY_RATE = "voice_rate"
    private const val KEY_PITCH = "voice_pitch"
    private const val KEY_CLOUD_NEPALI = "cloud_nepali_voice"
    private const val KEY_AZURE_REGION = "azure_speech_region"
    private const val KEY_AZURE_KEY = "azure_speech_key"

    const val DEFAULT_RATE = 0.95f
    const val DEFAULT_PITCH = 1.00f
    const val HEMKALA = "ne-NP-HemkalaNeural"
    const val SAGAR = "ne-NP-SagarNeural"

    private lateinit var prefs: SharedPreferences

    val engine = MutableStateFlow(VoiceEngine.SYSTEM)
    val nepaliSystemVoiceName = MutableStateFlow<String?>(null)
    val englishSystemVoiceName = MutableStateFlow<String?>(null)
    val rate = MutableStateFlow(DEFAULT_RATE)
    val pitch = MutableStateFlow(DEFAULT_PITCH)
    val cloudNepaliVoice = MutableStateFlow(HEMKALA)
    val azureRegion = MutableStateFlow("centralindia")

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        engine.value = prefs.getString(KEY_ENGINE, null)
            ?.let { saved -> VoiceEngine.entries.firstOrNull { it.name == saved } }
            ?: VoiceEngine.SYSTEM
        nepaliSystemVoiceName.value = prefs.getString(KEY_NEPALI_VOICE, null)
        englishSystemVoiceName.value = prefs.getString(KEY_ENGLISH_VOICE, null)
        rate.value = prefs.getFloat(KEY_RATE, DEFAULT_RATE).coerceIn(0.75f, 1.30f)
        pitch.value = prefs.getFloat(KEY_PITCH, DEFAULT_PITCH).coerceIn(0.80f, 1.20f)
        cloudNepaliVoice.value = prefs.getString(KEY_CLOUD_NEPALI, HEMKALA) ?: HEMKALA
        azureRegion.value = prefs.getString(KEY_AZURE_REGION, "centralindia")
            ?.takeIf { it.isNotBlank() } ?: "centralindia"
    }

    fun setEngine(value: VoiceEngine) {
        engine.value = value
        prefs.edit().putString(KEY_ENGINE, value.name).apply()
    }

    fun setNepaliSystemVoice(name: String?) {
        nepaliSystemVoiceName.value = name
        prefs.edit().putString(KEY_NEPALI_VOICE, name).apply()
    }

    fun setEnglishSystemVoice(name: String?) {
        englishSystemVoiceName.value = name
        prefs.edit().putString(KEY_ENGLISH_VOICE, name).apply()
    }

    fun setRate(value: Float) {
        rate.value = value.coerceIn(0.75f, 1.30f)
        prefs.edit().putFloat(KEY_RATE, rate.value).apply()
    }

    fun setPitch(value: Float) {
        pitch.value = value.coerceIn(0.80f, 1.20f)
        prefs.edit().putFloat(KEY_PITCH, pitch.value).apply()
    }

    fun setCloudNepaliVoice(name: String) {
        if (name != HEMKALA && name != SAGAR) return
        cloudNepaliVoice.value = name
        prefs.edit().putString(KEY_CLOUD_NEPALI, name).apply()
    }

    fun setAzureRegion(region: String) {
        val clean = region.trim().lowercase()
        if (clean.isBlank()) return
        azureRegion.value = clean
        prefs.edit().putString(KEY_AZURE_REGION, clean).apply()
    }

    fun azureKey(): String? =
        if (::prefs.isInitialized) {
            SecureStore.load(prefs, KEY_AZURE_KEY)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun hasAzureKey(): Boolean = !azureKey().isNullOrBlank()

    fun setAzureKey(value: String) {
        SecureStore.save(prefs, KEY_AZURE_KEY, value.trim())
    }
}
