package com.u4mess.ume.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.u4mess.ume.BuildConfig
import com.u4mess.ume.settings.VoiceEngine
import com.u4mess.ume.settings.VoiceSettings
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Sentence-buffered TTS with system voice selection, optional Azure neural TTS, and fallback. */
object VoiceOutputManager {

    private const val PREFS_NAME = "ume_voice"
    private const val KEY_ENABLED = "voice_replies"
    private const val KEY_RATE = "speech_rate"
    private const val DEFAULT_RATE = 0.95f
    private const val AZURE_ENGLISH_VOICE = "en-US-AvaMultilingualNeural"
    private const val AZURE_OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var nextUtteranceId = 0
    private var pendingUtterances = 0
    private val bufferedSentences = mutableListOf<String>()
    private val devanagari = Regex("[\\u0900-\\u097F]")

    private val cloudQueue = Channel<CloudSentence>(Channel.UNLIMITED)
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cloudClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    private var pendingCloud = 0
    private var cloudGeneration = 0
    private var activePlayer: MediaPlayer? = null
    private var activePlaybackResume: ((Boolean) -> Unit)? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _rate = MutableStateFlow(DEFAULT_RATE)
    val rate: StateFlow<Float> = _rate.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    var onAllDone: (() -> Unit)? = null

    private data class CloudSentence(val text: String, val generation: Int)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _rate.value = VoiceSettings.rate.value
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                applyAudioSettings()
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

        cloudScope.launch {
            for (item in cloudQueue) {
                if (item.generation != cloudGeneration) continue
                val played = runCatching { playCloud(item.text, item.generation) }
                    .getOrDefault(false)
                if (item.generation != cloudGeneration) continue
                if (!played) {
                    mainHandler.post { speakSystem(item.text) }
                }
                mainHandler.post { finishCloudItem() }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) stop()
    }

    fun setRate(value: Float) {
        VoiceSettings.setRate(value)
        _rate.value = VoiceSettings.rate.value
        tts?.setSpeechRate(VoiceSettings.rate.value)
    }

    fun setPitch(value: Float) {
        VoiceSettings.setPitch(value)
        tts?.setPitch(VoiceSettings.pitch.value)
    }

    fun refreshAudioSettings() {
        tts?.setSpeechRate(VoiceSettings.rate.value)
        tts?.setPitch(VoiceSettings.pitch.value)
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

    /** Installed system voices for the Settings voice pickers. */
    fun availableNepaliVoices(): List<Voice> =
        tts?.voices
            ?.filter { it.locale.language.equals("ne", ignoreCase = true) }
            ?.sortedByDescending { it.quality }
            .orEmpty()

    fun availableEnglishVoices(): List<Voice> {
        val voices = tts?.voices.orEmpty()
        val us = voices.filter {
            it.locale.language.equals("en", ignoreCase = true) &&
                it.locale.country.equals("US", ignoreCase = true)
        }
        return (us.ifEmpty {
            voices.filter { it.locale.language.equals("en", ignoreCase = true) }
        }).sortedByDescending { it.quality }
    }

    fun enqueueSentence(text: String) {
        val cleaned = clean(text)
        if (cleaned.isEmpty() || VoiceChatSession.muted.value) return
        mainHandler.post {
            if (VoiceSettings.engine.value == VoiceEngine.CLOUD_NEURAL &&
                VoiceSettings.hasAzureKey()
            ) {
                pendingCloud++
                _isSpeaking.value = true
                cloudQueue.trySend(CloudSentence(cleaned, cloudGeneration))
            } else if (!ttsReady) {
                bufferedSentences += cleaned
                _isSpeaking.value = true
            } else {
                speakSystem(cleaned)
            }
        }
    }

    fun stop() {
        mainHandler.post {
            bufferedSentences.clear()
            pendingUtterances = 0
            pendingCloud = 0
            cloudGeneration++
            while (cloudQueue.tryReceive().isSuccess) Unit
            _isSpeaking.value = false
            activePlaybackResume?.invoke(false)
            activePlaybackResume = null
            activePlayer?.let { player ->
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            activePlayer = null
            runCatching { tts?.stop() }
        }
    }

    fun destroy() {
        stop()
        cloudScope.launch {
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
        }
    }

    private fun speakSystem(sentence: String) {
        val engine = tts ?: return
        if (!ttsReady) {
            bufferedSentences += sentence
            _isSpeaking.value = true
            return
        }
        applyVoice(engine, sentence)
        engine.setSpeechRate(VoiceSettings.rate.value)
        engine.setPitch(VoiceSettings.pitch.value)
        pendingUtterances++
        _isSpeaking.value = true
        val utteranceId = "ume-${nextUtteranceId++}"
        val result = engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            onUtteranceFinished()
        }
    }

    private fun applyVoice(engine: TextToSpeech, sentence: String) {
        val nepali = devanagari.containsMatchIn(sentence)
        val voices = if (nepali) availableNepaliVoices() else availableEnglishVoices()
        val preferredName = if (nepali) {
            VoiceSettings.nepaliSystemVoiceName.value
        } else {
            VoiceSettings.englishSystemVoiceName.value
        }
        val voice = preferredName?.let { name -> voices.firstOrNull { it.name == name } }
            ?: voices.maxByOrNull { it.quality }
        if (voice != null) {
            runCatching { engine.voice = voice }
        }
        val locale = if (nepali) {
            voice?.locale ?: Locale("ne", "NP")
        } else {
            voice?.locale ?: Locale.US
        }
        val status = engine.setLanguage(locale)
        if (nepali && status < TextToSpeech.LANG_AVAILABLE) {
            engine.setLanguage(Locale.getDefault())
        }
    }

    private fun applyAudioSettings() {
        tts?.setSpeechRate(VoiceSettings.rate.value)
        tts?.setPitch(VoiceSettings.pitch.value)
    }

    private fun flushBuffered() {
        val queued = bufferedSentences.toList()
        bufferedSentences.clear()
        queued.forEach { sentence -> speakSystem(sentence) }
    }

    private fun onUtteranceFinished() {
        if (pendingUtterances <= 0) return
        pendingUtterances--
        maybeAllDone()
    }

    private fun finishCloudItem() {
        if (pendingCloud > 0) pendingCloud--
        maybeAllDone()
    }

    private fun maybeAllDone() {
        if (pendingUtterances > 0 || pendingCloud > 0) return
        _isSpeaking.value = false
        onAllDone?.invoke()
    }

    private suspend fun playCloud(sentence: String, generation: Int): Boolean {
        val key = VoiceSettings.azureKey() ?: return false
        val audio = synthesizeAzure(sentence, key) ?: return false
        if (generation != cloudGeneration) return false
        val file = runCatching {
            File.createTempFile("mero-tts-", ".mp3", appContext.cacheDir)
        }.getOrNull() ?: return false
        return try {
            file.writeBytes(audio)
            playFile(file)
        } finally {
            file.delete()
        }
    }

    private suspend fun synthesizeAzure(sentence: String, key: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val nepal = devanagari.containsMatchIn(sentence)
            val voice = if (nepal) VoiceSettings.cloudNepaliVoice.value else AZURE_ENGLISH_VOICE
            val language = if (nepal) "ne-NP" else "en-US"
            val region = VoiceSettings.azureRegion.value
                .trim()
                .lowercase(Locale.US)
                .takeIf { it.matches(Regex("[a-z0-9-]+")) }
                ?: "centralindia"
            val percent = ((VoiceSettings.rate.value - 1f) * 100).roundToInt()
            val rate = if (percent >= 0) "+$percent%" else "$percent%"
            val ssml = "<speak version='1.0' xml:lang='$language'>" +
                "<voice name='${escapeXml(voice)}'><prosody rate='$rate'>" +
                escapeXml(sentence) +
                "</prosody></voice></speak>"
            val request = runCatching {
                Request.Builder()
                    .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                    .header("Ocp-Apim-Subscription-Key", key)
                    .header("Content-Type", "application/ssml+xml")
                    .header("X-Microsoft-OutputFormat", AZURE_OUTPUT_FORMAT)
                    .header("User-Agent", "MeroAgent")
                    .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
                    .build()
            }.getOrNull() ?: return@withContext null
            runCatching {
                cloudClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.bytes()?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull()
        }

    private suspend fun playFile(file: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val player = MediaPlayer()
            var finished = false
            fun finish(success: Boolean) {
                if (finished) return
                finished = true
                activePlaybackResume = null
                runCatching { player.release() }
                if (activePlayer === player) activePlayer = null
                if (continuation.isActive) continuation.resume(success)
            }
            activePlayer = player
            activePlaybackResume = { success -> finish(success) }
            continuation.invokeOnCancellation {
                activePlaybackResume = null
                runCatching { player.release() }
                if (activePlayer === player) activePlayer = null
            }
            player.setOnPreparedListener { if (continuation.isActive) it.start() }
            player.setOnCompletionListener { finish(true) }
            player.setOnErrorListener { _, _, _ ->
                finish(false)
                true
            }
            runCatching {
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            }.onFailure { finish(false) }
        }
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun clean(text: String): String =
        text
            .replace(Regex("[`*#]"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
