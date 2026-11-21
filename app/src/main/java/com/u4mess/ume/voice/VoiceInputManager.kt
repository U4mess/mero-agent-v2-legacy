package com.u4mess.ume.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread wrapper around Android's SpeechRecognizer with observable UI state. */
object VoiceInputManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private lateinit var appContext: Context
    private var recognizer: SpeechRecognizer? = null
    private var finalResult: ((String) -> Unit)? = null
    private var ignoreResults = false
    private var stopRequested = false
    private val hasDeliveredResult = AtomicBoolean(false)

    private val stopTimeout = Runnable {
        if (_isListening.value && stopRequested) {
            finishSession("No speech detected. Try again.")
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startListening(onFinalResult: (String) -> Unit) {
        runOnMain {
            if (!::appContext.isInitialized) {
                _errorMessage.value = "Voice input is not ready."
                return@runOnMain
            }
            if (!hasPermission(appContext)) {
                _errorMessage.value = "Microphone permission is required."
                _isListening.value = false
                return@runOnMain
            }
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                _errorMessage.value = "Speech recognition is unavailable on this device."
                _isListening.value = false
                return@runOnMain
            }

            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                    it.setRecognitionListener(listener)
                }
            }

            mainHandler.removeCallbacks(stopTimeout)
            ignoreResults = false
            stopRequested = false
            hasDeliveredResult.set(false)
            finalResult = onFinalResult
            _errorMessage.value = null
            _partialTranscript.value = ""
            _rmsLevel.value = 0f
            _isListening.value = true

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }

            runCatching { recognizer?.startListening(intent) }.onFailure { throwable ->
                finishSession(throwable.message ?: "Could not start voice input.")
            }
        }
    }

    fun stopListening() {
        runOnMain {
            if (!_isListening.value) return@runOnMain
            stopRequested = true
            _rmsLevel.value = 0f
            runCatching { recognizer?.stopListening() }.onFailure { throwable ->
                finishSession(throwable.message ?: "Could not stop voice input.")
            }
            mainHandler.removeCallbacks(stopTimeout)
            mainHandler.postDelayed(stopTimeout, 2_500L)
        }
    }

    fun cancel() {
        runOnMain {
            ignoreResults = true
            hasDeliveredResult.set(true)
            finalResult = null
            stopRequested = false
            mainHandler.removeCallbacks(stopTimeout)
            runCatching { recognizer?.cancel() }
            _isListening.value = false
            _partialTranscript.value = ""
            _rmsLevel.value = 0f
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun destroy() {
        runOnMain {
            mainHandler.removeCallbacks(stopTimeout)
            finalResult = null
            ignoreResults = true
            runCatching { recognizer?.destroy() }
            recognizer = null
            _isListening.value = false
            _partialTranscript.value = ""
            _rmsLevel.value = 0f
        }
    }

    private fun finishSession(message: String?) {
        mainHandler.removeCallbacks(stopTimeout)
        hasDeliveredResult.set(true)
        _isListening.value = false
        _rmsLevel.value = 0f
        stopRequested = false
        finalResult = null
        if (!message.isNullOrBlank()) {
            _errorMessage.value = message
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _errorMessage.value = null
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            _rmsLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            if (ignoreResults) {
                ignoreResults = false
                return
            }
            if (hasDeliveredResult.get()) return
            finishSession(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            if (ignoreResults) {
                ignoreResults = false
                return
            }
            if (!hasDeliveredResult.compareAndSet(false, true)) return
            val text = bestResult(results).ifBlank { _partialTranscript.value }
            mainHandler.removeCallbacks(stopTimeout)
            _isListening.value = false
            _rmsLevel.value = 0f
            stopRequested = false
            _partialTranscript.value = text
            val callback = finalResult
            finalResult = null
            if (text.isNotBlank()) {
                _errorMessage.value = null
                callback?.invoke(text)
            } else {
                _errorMessage.value = "No speech detected. Try again."
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestResult(partialResults)
            if (text.isNotBlank()) _partialTranscript.value = text
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun bestResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "No speech detected. Try again."

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "Speech recognition needs a network connection."

        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Try again."
        SpeechRecognizer.ERROR_CLIENT -> "Voice input stopped. Try again."
        else -> "Voice input failed (code $error)."
    }
}
