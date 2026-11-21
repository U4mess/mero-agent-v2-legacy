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

enum class ChatMode(val label: String) {
    AUTO("Auto"),
    LOCAL("Local"),
    CLOUD("Cloud"),
}

object ChatController {

    data class Message(
        val id: Long,
        val role: ChatRole,
        val text: String,
        val streaming: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        val proactive: Boolean = false,
        val agent: UiAgentResult? = null,
    )

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    val mode = MutableStateFlow(ChatMode.AUTO)

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

    fun setMode(value: ChatMode) {
        mode.value = value
    }

    fun postProactiveSummary(text: String) {
        val summary = text.trim()
        if (summary.isEmpty()) return
        val id = nextId++
        _messages.update {
            it + Message(
                id = id,
                role = ChatRole.ASSISTANT,
                text = summary,
                proactive = true,
            )
        }
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
            val cleaned = HybridRouter.clean(prompt)
            when {
                HybridRouter.route(prompt) == HybridRoute.CLOUD -> {
                    runCloudAction(cleaned, assistantId)
                }

                mode.value == ChatMode.CLOUD -> {
                    generateCloudChat(cleaned, assistantId)
                }

                mode.value == ChatMode.LOCAL -> {
                    generateLocal(cleaned, assistantId)
                }

                LocalLlmEngine.chatStatus.value is ModelStatus.Ready -> {
                    generateLocal(cleaned, assistantId)
                }

                CloudSettings.hasKey() -> {
                    generateCloudChat(cleaned, assistantId)
                }

                else -> {
                    replace(
                        assistantId,
                        "Auto couldn't find a loaded local model or a cloud API key. " +
                            "Load a .gguf in Models or add a key in Settings -> Cloud Action Brain.",
                    )
                }
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

    private suspend fun generateCloudChat(prompt: String, assistantId: Long) {
        if (prompt.isBlank()) {
            replace(assistantId, "Tell me what to do, or just say hello.")
            return
        }
        if (!CloudSettings.hasKey()) {
            replace(
                assistantId,
                "Cloud chat needs a ${CloudSettings.providerLabel()} API key. " +
                    "Add one in Settings -> Cloud Action Brain.",
            )
            notifyIsland("Add ${CloudSettings.providerLabel()} key")
            return
        }

        val system = buildSystemPrompt(prompt, local = false)
        val turns = _messages.value
            .filter { it.id != assistantId && it.text.isNotBlank() }
            .takeLast(8)
            .map { ChatTurn(it.role, it.text) }

        CloudLlmClient.chatCompletion(turns, system).fold(
            onSuccess = { answer ->
                replace(assistantId, answer.trim().ifBlank { "The cloud model returned no text." })
            },
            onFailure = { throwable ->
                replace(
                    assistantId,
                    "Cloud chat failed: ${throwable.message ?: "unknown error"}",
                )
            },
        )
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
                "Add a ${CloudSettings.providerLabel()} API key in Settings → Cloud Action Brain before using phone actions.",
            )
            notifyIsland("Add ${CloudSettings.providerLabel()} key")
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
            val stopped = UiAgentResult(false, "Stopped by user.", emptyList())
            replace(assistantId, "Stopped by user.")
            setAgent(assistantId, stopped)
            IslandAgent.finish("Stopped")
            recordAction(goal, "Stopped by user.", emptyList())
        } else {
            val stopped = result.message.contains("stopped", ignoreCase = true)
            val summary = when {
                stopped -> "Stopped by user."
                result.success -> result.message
                else -> "Action failed: ${result.message}"
            }
            replace(assistantId, summary)
            setAgent(assistantId, result)
            IslandAgent.finish(
                when {
                    stopped -> "Stopped"
                    result.success -> "Done"
                    else -> "Failed"
                },
            )
            recordAction(goal, summary, result.steps)
        }

        scope.launch {
            delay(2000)
            IslandAgent.clearIfFinished()
        }
    }

    private suspend fun buildSystemPrompt(query: String, local: Boolean = true): String {
        val memories = retrieveMemories(query)
        val modelName = ModelManager.chatSelection.value?.displayName
        return buildString {
            if (local) {
                if (modelName != null) {
                    append("You are Ume, a private on-device AI assistant running locally via $modelName.")
                } else {
                    append("You are Ume, a private on-device AI assistant running locally.")
                }
            } else {
                append("You are Ume, a helpful AI assistant with access to the user's memories.")
            }
            append(" Answer directly and concisely.")
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
                packageName = "Ume Agent",
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

    private fun setAgent(id: Long, result: UiAgentResult) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(agent = result) else it }
        }
    }

    private fun textOf(id: Long): String =
        _messages.value.firstOrNull { it.id == id }?.text.orEmpty()
}
