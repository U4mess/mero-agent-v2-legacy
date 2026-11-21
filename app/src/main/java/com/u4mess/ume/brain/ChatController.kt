package com.u4mess.ume.brain

import android.content.Context
import com.u4mess.ume.memory.MemoryEntity
import com.u4mess.ume.memory.MemoryRepository
import com.u4mess.ume.memory.MemorySource
import com.u4mess.ume.sensors.ScreenReaderAccessibilityService
import com.u4mess.ume.service.IslandAgent
import com.u4mess.ume.service.PromptInbox
import com.u4mess.ume.settings.CloudSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private var agentJob: Job? = null
    private lateinit var appContext: Context
    private lateinit var repository: MemoryRepository

    fun start(context: Context, repository: MemoryRepository) {
        if (started) return
        appContext = context.applicationContext
        this.repository = repository
        started = true

        IslandAgent.setStopHandler { stop() }

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
        val agent = agentJob
        if (agent != null && agent.isActive) {
            agent.cancel()
            return
        }
        LocalLlmEngine.stopGeneration()
    }

    private suspend fun generate(prompt: String) {
        _generating.value = true
        val assistantId = nextId++
        _messages.update { it + Message(assistantId, ChatRole.ASSISTANT, "", streaming = true) }

        try {
            when (HybridRouter.route(prompt)) {
                HybridRoute.LOCAL -> generateLocal(HybridRouter.clean(prompt), assistantId)
                HybridRoute.CLOUD -> runCloudAction(HybridRouter.clean(prompt), assistantId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            if (textOf(assistantId).isBlank()) {
                replace(assistantId, "Something went wrong: ${throwable.message ?: "unknown error"}")
            }
        } finally {
            _messages.update { list ->
                list.map { if (it.id == assistantId) it.copy(streaming = false) else it }
            }
            _generating.value = false
        }
    }

    private suspend fun generateLocal(prompt: String, assistantId: Long) {
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
    }

    private suspend fun runCloudAction(goal: String, assistantId: Long) {
        if (goal.isBlank()) {
            replace(assistantId, "Tell me what to do, for example: /do open Settings.")
            return
        }
        if (!CloudSettings.enabled.value) {
            replace(
                assistantId,
                "Hybrid cloud actions are off. Turn them on in Settings → Cloud Action Brain, or use /local.",
            )
            notifyIsland("Cloud actions off")
            return
        }
        if (!CloudSettings.hasKey()) {
            replace(
                assistantId,
                "Add a DeepSeek API key in Settings → Cloud Action Brain before using phone actions.",
            )
            notifyIsland("Add DeepSeek key")
            return
        }
        if (ScreenReaderAccessibilityService.instance == null) {
            replace(
                assistantId,
                "Enable Ume Accessibility in Settings so I can read and tap the screen.",
            )
            notifyIsland("Accessibility off")
            return
        }

        var outcome: UiAgentResult? = null
        val job = scope.launch {
            outcome = try {
                UiAgentLoop.run(appContext, goal) { status ->
                    IslandAgent.update(status, active = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                UiAgentResult(false, throwable.message ?: "Action failed.", emptyList())
            }
        }
        agentJob = job
        try {
            job.join()
        } finally {
            agentJob = null
        }

        val result = outcome
        if (result == null) {
            replace(assistantId, "Action stopped.")
            IslandAgent.finish("Stopped")
            recordAction(goal, "Stopped by user.", emptyList())
        } else {
            val summary = if (result.success) result.message else "Action failed: ${result.message}"
            val chatText = buildString {
                append(summary)
                if (result.steps.isNotEmpty()) {
                    append("\n\n")
                    append(result.steps.takeLast(6).joinToString("\n"))
                }
            }
            replace(assistantId, chatText)
            IslandAgent.finish(if (result.success) "Done" else "Failed")
            recordAction(goal, summary, result.steps)
        }

        scope.launch {
            delay(2000)
            IslandAgent.clearIfFinished()
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

    private suspend fun recordAction(goal: String, summary: String, steps: List<String>) {
        withContext(Dispatchers.IO) {
            repository.insert(
                source = MemorySource.ACTION,
                packageName = "",
                title = "Action: ${goal.take(60)}",
                content = buildString {
                    append(summary)
                    if (steps.isNotEmpty()) {
                        append("\n\n")
                        append(steps.joinToString("\n"))
                    }
                },
            )
        }
    }

    private fun notifyIsland(text: String) {
        IslandAgent.update(text, active = false)
        scope.launch {
            delay(2200)
            IslandAgent.clearIfFinished()
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
