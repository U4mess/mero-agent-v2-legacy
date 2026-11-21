package com.u4mess.ume.brain

import android.util.Log
import com.u4mess.ume.BuildConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM wrapper. Every SDK call is serialized on one background executor.
 * Conversation is always closed before Engine, and close operations are guarded.
 */
object LiteRtLmEngine {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mero-litert-lm").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Unloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    suspend fun load(modelPath: String, cacheDir: String): Boolean = withContext(dispatcher) {
        closed.set(false)
        closeResources()
        _status.value = ModelStatus.Loading

        val loaded = createEngine(modelPath, cacheDir, useGpu = true)
            ?: createEngine(modelPath, cacheDir, useGpu = false)
        if (loaded == null) {
            _status.value = ModelStatus.Error("Could not load .litertlm model")
            return@withContext false
        }
        engine = loaded
        _status.value = ModelStatus.Ready
        true
    }

    fun generate(
        system: String,
        turns: List<ChatTurn>,
        imagePath: String? = null,
        audioPath: String? = null,
    ): Flow<String> = channelFlow {
        val pair = withContext(dispatcher) {
            closeConversationInternal()
            val currentEngine = engine ?: error("LiteRT-LM model is not loaded")
            val currentIndex = turns.indexOfLast { it.role == ChatRole.USER }
            val currentText = turns.getOrNull(currentIndex)?.text
                ?: error("No user message to send")
            val history = turns.mapIndexedNotNull { index, turn ->
                if (index == currentIndex) null else when (turn.role) {
                    ChatRole.USER -> Message.user(turn.text)
                    ChatRole.ASSISTANT -> Message.model(turn.text)
                }
            }
            val config = ConversationConfig(
                systemInstruction = Contents.of(system),
                initialMessages = history,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
            )
            val active = currentEngine.createConversation(config)
            conversation = active
            val prompt = if (system.contains("OCR transcripts and notes")) {
                "Use the OCR memory context from the system instructions. Quote the relevant " +
                    "OCR line directly; do not ask me to provide the screenshot or context.\n\n" +
                    currentText
            } else {
                currentText
            }
            active to prompt
        }
        val activeConversation = pair.first
        val prompt = pair.second
        try {
            withContext(dispatcher) {
                val parts = buildList {
                    imagePath?.let { add(Content.ImageFile(it)) }
                    audioPath?.let { add(Content.AudioFile(it)) }
                    add(Content.Text(prompt))
                }
                val replies = activeConversation.sendMessageAsync(
                    Contents.of(*parts.toTypedArray()),
                )
                replies.collect { message ->
                    val text = message.toString()
                    if (text.isNotEmpty()) send(text)
                }
            }
        } finally {
            withContext(dispatcher) { closeConversationInternal() }
        }
    }

    fun cancel() {
        executor.execute {
            runCatching { conversation?.cancelProcess() }
        }
    }

    suspend fun close() = unload()

    suspend fun unload() {
        withContext(dispatcher) {
            if (!closed.compareAndSet(false, true)) return@withContext
            closeResources()
            _status.value = ModelStatus.Unloaded
        }
    }

    private fun createEngine(modelPath: String, cacheDir: String, useGpu: Boolean): Engine? {
        val candidate = runCatching {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                    visionBackend = if (useGpu) Backend.GPU() else Backend.CPU(),
                    audioBackend = Backend.CPU(),
                    cacheDir = cacheDir,
                ),
            )
        }.getOrNull() ?: return null
        return runCatching {
            candidate.initialize()
            candidate
        }.getOrElse { throwable ->
            if (BuildConfig.DEBUG) {
                Log.e("MeroLiteRt", "LiteRT-LM initialization failed", throwable)
            }
            runCatching { candidate.close() }
            null
        }
    }

    private fun closeResources() {
        closeConversationInternal()
        engine?.let { value ->
            runCatching { value.close() }
            engine = null
        }
    }

    private fun closeConversationInternal() {
        conversation?.let { value ->
            runCatching { value.close() }
            conversation = null
        }
    }
}
