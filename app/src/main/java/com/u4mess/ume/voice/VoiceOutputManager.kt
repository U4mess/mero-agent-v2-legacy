package com.u4mess.ume.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.u4mess.ume.BuildConfig
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sentence-buffered TTS output with interrupt support and completion callbacks. */
object VoiceOutputManager {

    private const val PREFS_NAME = "ume_voice"
    private const val KEY_ENABLED = "voice_replies"
    private const val KEY_RATE = "speech_rate"
    private const val DEFAULT_RATE = 1.05f

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var nextUtteranceId = 0
    private var pendingUtterances = 0
    private val bufferedSentences = mutableListOf<String>()
    private val devanagari = Regex("[\\u0900-\\u097F]")

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _rate = MutableStateFlow(DEFAULT_RATE)
    val rate: StateFlow<Float> = _rate.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    var onAllDone: (() -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _rate.value = prefs.getFloat(KEY_RATE, DEFAULT_RATE).coerceIn(0.8f, 1.5f)
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(_rate.value)
                flushBuffered()
            } else {
                if (BuildConfig.DEBUG) {
                    Log.e("UmeVoice", "TextToSpeech initialization failed ($status)")
                }
                ttsReady = false
                bufferedSentences.clear()
                _isSpeaking.value = false
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                mainHandler.post { onUtteranceFinished() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { onUtteranceFinished() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { onUtteranceFinished() }
            }
        })
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) stop()
    }

    fun setRate(value: Float) {
        _rate.value = value.coerceIn(0.8f, 1.5f)
        prefs.edit().putFloat(KEY_RATE, _rate.value).apply()
        tts?.setSpeechRate(_rate.value)
    }

    fun enqueueSentence(text: String) {
        val cleaned = clean(text)
        if (cleaned.isEmpty() || VoiceChatSession.muted.value) return
        mainHandler.post {
            if (!ttsReady) {
                bufferedSentences += cleaned
                _isSpeaking.value = true
            } else {
                speak(cleaned)
            }
        }
    }

    fun requestInstallNepaliVoiceData(context: Context) {
        val engine = tts
        val status = engine?.isLanguageAvailable(Locale("ne", "NP"))
            ?: TextToSpeech.LANG_MISSING_DATA
        if (status >= TextToSpeech.LANG_AVAILABLE) return
        runCatching {
            context.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun stop() {
        mainHandler.post {
            bufferedSentences.clear()
            pendingUtterances = 0
            _isSpeaking.value = false
            runCatching { tts?.stop() }
        }
    }

    fun destroy() {
        mainHandler.post {
            bufferedSentences.clear()
            pendingUtterances = 0
            _isSpeaking.value = false
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
        }
    }

    private fun speak(sentence: String) {
        val engine = tts ?: return
        if (devanagari.containsMatchIn(sentence)) {
            val status = engine.setLanguage(Locale("ne", "NP"))
            if (status < TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(Locale.getDefault())
            }
        } else {
            engine.setLanguage(Locale.US)
        }
        pendingUtterances++
        _isSpeaking.value = true
        val utteranceId = "ume-${nextUtteranceId++}"
        val result = engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            onUtteranceFinished()
        }
    }

    private fun flushBuffered() {
        val queued = bufferedSentences.toList()
        bufferedSentences.clear()
        queued.forEach { sentence -> speak(sentence) }
    }

    private fun onUtteranceFinished() {
        if (pendingUtterances <= 0) return
        pendingUtterances--
        if (pendingUtterances == 0) {
            _isSpeaking.value = false
            onAllDone?.invoke()
        }
    }

    private fun clean(text: String): String =
        text
            .replace(Regex("[`*#]"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
