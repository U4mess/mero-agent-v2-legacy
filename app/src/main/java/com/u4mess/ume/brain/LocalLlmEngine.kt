package com.u4mess.ume.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

enum class ChatRole { USER, ASSISTANT }

data class ChatTurn(val role: ChatRole, val text: String)

enum class ChatTemplate {
    CHATML,
    LLAMA3,
    GEMMA,
    PLAIN;

    fun format(system: String, turns: List<ChatTurn>): String = when (this) {
        CHATML -> buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            turns.forEach { turn ->
                append("<|im_start|>")
                append(if (turn.role == ChatRole.USER) "user" else "assistant")
                append("\n").append(turn.text).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }

        LLAMA3 -> buildString {
            append("<|begin_of_text|>")
            append("<|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
            turns.forEach { turn ->
                append("<|start_header_id|>")
                append(if (turn.role == ChatRole.USER) "user" else "assistant")
                append("<|end_header_id|>\n\n").append(turn.text).append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }

        GEMMA -> buildString {
            val withSystem = if (system.isBlank()) {
                turns
            } else {
                val index = turns.indexOfFirst { it.role == ChatRole.USER }
                if (index >= 0) {
                    turns.toMutableList().also { list ->
                        list[index] = list[index].copy(text = "$system\n\n${list[index].text}")
                    }
                } else {
                    listOf(ChatTurn(ChatRole.USER, system)) + turns
                }
            }
            withSystem.forEach { turn ->
                append("<start_of_turn>")
                append(if (turn.role == ChatRole.USER) "user" else "model")
                append("\n").append(turn.text).append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }

        PLAIN -> buildString {
            append("System: ").append(system).append('\n')
            turns.forEach { turn ->
                append(if (turn.role == ChatRole.USER) "User: " else "Assistant: ")
                append(turn.text).append('\n')
            }
            append("Assistant: ")
        }
    }

    companion object {
        fun detect(fileName: String, metadata: String?): ChatTemplate {
            val template = metadata.orEmpty()
            if (template.contains("<|im_start|>")) return CHATML
            if (template.contains("<|start_header_id|>")) return LLAMA3
            if (template.contains("<start_of_turn>")) return GEMMA

            val name = fileName.lowercase()
            return when {
                name.contains("qwen") || name.contains("chatml") -> CHATML
                name.contains("llama-3") || name.contains("llama3") || name.contains("llama_3") -> LLAMA3
                name.contains("gemma") -> GEMMA
                else -> PLAIN
            }
        }
    }
}

sealed interface ModelStatus {
    data object Unloaded : ModelStatus
    data object Loading : ModelStatus
    data object Ready : ModelStatus
    data class Error(val message: String) : ModelStatus
}

object LocalLlmEngine {

    val chatStatus = MutableStateFlow<ModelStatus>(ModelStatus.Unloaded)
    val embeddingStatus = MutableStateFlow<ModelStatus>(ModelStatus.Unloaded)
    val chatTemplate = MutableStateFlow(ChatTemplate.PLAIN)

    val recommendedThreads: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    private var handle = 0L
    private val handleLock = Any()

    private fun engineHandle(): Long = synchronized(handleLock) {
        if (handle == 0L) handle = NativeLlama.nativeCreate()
        handle
    }

    suspend fun loadChat(
        path: String,
        contextSize: Int = DEFAULT_CONTEXT_SIZE,
        threads: Int = recommendedThreads,
    ): Boolean = withContext(Dispatchers.IO) {
        chatStatus.value = ModelStatus.Loading
        val result = runCatching {
            NativeLlama.nativeLoadChat(engineHandle(), path, contextSize, threads)
        }
        val loaded = result.getOrDefault(false)
        if (loaded) {
            val metadata = runCatching { NativeLlama.nativeChatTemplate(engineHandle()) }.getOrNull()
            chatTemplate.value = ChatTemplate.detect(path.substringAfterLast('/'), metadata)
            chatStatus.value = ModelStatus.Ready
        } else {
            chatStatus.value = ModelStatus.Error(
                result.exceptionOrNull()?.message ?: "Failed to load chat model",
            )
        }
        loaded
    }

    suspend fun unloadChat() = withContext(Dispatchers.IO) {
        if (handle != 0L) runCatching { NativeLlama.nativeUnloadChat(handle) }
        chatStatus.value = ModelStatus.Unloaded
        chatTemplate.value = ChatTemplate.PLAIN
    }

    suspend fun loadEmbedding(path: String, threads: Int = recommendedThreads): Boolean =
        withContext(Dispatchers.IO) {
            embeddingStatus.value = ModelStatus.Loading
            val result = runCatching {
                NativeLlama.nativeLoadEmbedding(engineHandle(), path, threads)
            }
            val loaded = result.getOrDefault(false)
            embeddingStatus.value = if (loaded) {
                ModelStatus.Ready
            } else {
                ModelStatus.Error(
                    result.exceptionOrNull()?.message ?: "Embedding model must be 384-dimensional",
                )
            }
            loaded
        }

    suspend fun unloadEmbedding() = withContext(Dispatchers.IO) {
        if (handle != 0L) runCatching { NativeLlama.nativeUnloadEmbedding(handle) }
        embeddingStatus.value = ModelStatus.Unloaded
    }

    fun formatPrompt(system: String, turns: List<ChatTurn>): String =
        chatTemplate.value.format(system, turns)

    fun generate(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Flow<String> = channelFlow {
        if (chatStatus.value !is ModelStatus.Ready) {
            close(IllegalStateException("Chat model is not loaded"))
            return@channelFlow
        }

        val sink = TokenSink { token -> trySend(token).isSuccess }
        try {
            withContext(Dispatchers.IO) {
                NativeLlama.nativeGenerate(engineHandle(), prompt, maxTokens, sink)
            }
        } catch (throwable: Throwable) {
            close(throwable)
            return@channelFlow
        }
        close()
    }

    fun stopGeneration() {
        val current = handle
        if (current != 0L) runCatching { NativeLlama.nativeStop(current) }
    }

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (embeddingStatus.value !is ModelStatus.Ready) return@withContext null
        runCatching { NativeLlama.nativeEmbed(engineHandle(), text) }
            .getOrNull()
            ?.takeIf { it.size == EMBEDDING_DIMENSIONS }
    }

    private const val DEFAULT_CONTEXT_SIZE = 4096
    private const val DEFAULT_MAX_TOKENS = 512
    private const val EMBEDDING_DIMENSIONS = 384
}
