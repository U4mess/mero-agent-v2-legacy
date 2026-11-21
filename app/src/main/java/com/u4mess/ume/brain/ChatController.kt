package com.u4mess.ume.brain

import com.u4mess.ume.memory.MemoryEntity
import com.u4mess.ume.memory.MemoryRepository
import com.u4mess.ume.service.PromptInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ChatController {

    data class Message(
        val id: Long,
        val role: ChatRole,
        val text: String,
        val streaming: Boolean = false,
    )

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = Channel<String>(Channel.UNLIMITED)
    private var nextId = 0L
    private var islandConsumed = 0
    private var started = false
    private lateinit var repository: MemoryRepository

    fun start(repository: MemoryRepository) {
        if (started) return
        this.repository = repository
        started = true

        scope.launch {
            for (prompt in pending) {
                _messages.update { it + Message(nextId++, ChatRole.USER, prompt) }
                generate(prompt)
            }
        }

        scope.launch {
            PromptInbox.prompts.collect { prompts ->
                if (prompts.size > islandConsumed) {
                    prompts.subList(islandConsumed, prompts.size).forEach { pending.trySend(it) }
                    islandConsumed = prompts.size
                }
            }
        }
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isNotEmpty()) pending.trySend(text)
    }

    fun stop() {
        LocalLlmEngine.stopGeneration()
    }

    private suspend fun generate(prompt: String) {
        _generating.value = true
        val assistantId = nextId++
        _messages.update { it + Message(assistantId, ChatRole.ASSISTANT, "", streaming = true) }

        try {
            if (LocalLlmEngine.chatStatus.value !is ModelStatus.Ready) {
                replace(
                    assistantId,
                    "No chat model loaded. Open Models, choose a .gguf file and tap Load.",
                )
                return
            }

            val system = buildSystemPrompt(prompt)
            val turns = _messages.value
                .filter { it.id != assistantId && it.text.isNotBlank() }
                .takeLast(8)
                .map { ChatTurn(it.role, it.text) }

            val formatted = LocalLlmEngine.formatPrompt(system, turns)
            LocalLlmEngine.generate(formatted).collect { piece -> append(assistantId, piece) }

            if (textOf(assistantId).isBlank()) {
                replace(assistantId, "The model returned no text.")
            }
        } catch (throwable: Throwable) {
            if (textOf(assistantId).isBlank()) {
                replace(assistantId, "Generation failed: ${throwable.message ?: "unknown error"}")
            }
        } finally {
            _messages.update { list ->
                list.map { if (it.id == assistantId) it.copy(streaming = false) else it }
            }
            _generating.value = false
        }
    }

    private suspend fun buildSystemPrompt(query: String): String {
        val memories = retrieveMemories(query)
        return buildString {
            append("You are Ume, a private on-device assistant. Answer directly and concisely.")
            if (memories.isNotEmpty()) {
                append("\n\nRelevant memories from this device:\n")
                memories.forEach { memory ->
                    append("- ")
                    if (memory.title.isNotBlank()) append(memory.title).append(": ")
                    append(memory.content.replace('\n', ' ').take(280))
                    append('\n')
                }
            }
        }
    }

    private suspend fun retrieveMemories(query: String): List<MemoryEntity> {
        if (LocalLlmEngine.embeddingStatus.value is ModelStatus.Ready) {
            LocalLlmEngine.embed(query)?.let { vector ->
                val matches = withContext(Dispatchers.IO) {
                    repository.nearestNeighbors(vector, limit = 5)
                }
                if (matches.isNotEmpty()) return matches
            }
        }

        return withContext(Dispatchers.IO) {
            (repository.search(keyword = query.take(80), limit = 5) + repository.recent(limit = 5))
                .distinctBy { it.id }
                .take(5)
        }
    }

    private fun append(id: Long, text: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(text = it.text + text) else it }
        }
    }

    private fun replace(id: Long, text: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(text = text) else it }
        }
    }

    private fun textOf(id: Long): String =
        _messages.value.firstOrNull { it.id == id }?.text.orEmpty()
}
