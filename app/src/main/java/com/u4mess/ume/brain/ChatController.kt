package com.u4mess.ume.brain

import android.content.Context
import android.util.Log
import com.u4mess.ume.memory.MemoryEntity
import com.u4mess.ume.memory.MemoryRepository
import com.u4mess.ume.memory.MemorySource
import com.u4mess.ume.net.WebResult
import com.u4mess.ume.net.WebSearchClient
import com.u4mess.ume.sensors.ScreenReaderAccessibilityService
import com.u4mess.ume.service.IslandAgent
import com.u4mess.ume.service.PromptInbox
import com.u4mess.ume.settings.CloudSettings
import com.u4mess.ume.settings.LanguageMode
import com.u4mess.ume.settings.LanguageSettings
import com.u4mess.ume.settings.WebSearchSettings
import com.u4mess.ume.voice.VoiceChatSession
import com.u4mess.ume.voice.VoiceOutputManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
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
        val webSources: List<WebResult> = emptyList(),
        val attachment: ChatAttachment? = null,
        val sessionId: String = "",
    )

    private data class IncomingPrompt(
        val text: String,
        val attachment: ChatAttachment? = null,
    )

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val sessionMessages = mutableMapOf<String, List<Message>>()

    val mode = MutableStateFlow(ChatMode.AUTO)
    val webEnabled = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = Channel<IncomingPrompt>(Channel.UNLIMITED)
    private val MEMORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
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
        initializeSessions()

        IslandAgent.setStopHandler { stop() }

        scope.launch {
            for (incoming in pending) {
                val sessionId = _currentSessionId.value
                val firstUserMessage = _messages.value.none { it.role == ChatRole.USER }
                _messages.update {
                    it + Message(
                        nextId++,
                        ChatRole.USER,
                        incoming.text,
                        attachment = incoming.attachment,
                        sessionId = sessionId,
                    )
                }
                if (firstUserMessage) autoTitle(incoming.text)
                persistCurrent()
                generate(incoming.text, incoming.attachment)
            }
        }

        scope.launch {
            PromptInbox.prompts.collect { prompts ->
                if (prompts.size > islandConsumed) {
                    prompts.subList(islandConsumed, prompts.size)
                        .forEach { pending.trySend(IncomingPrompt(it)) }
                    islandConsumed = prompts.size
                }
            }
        }
    }

    fun send(prompt: String, attachment: ChatAttachment? = null) {
        val text = prompt.trim().ifBlank {
            when {
                attachment?.imagePath != null -> when (LanguageSettings.mode.value) {
                    LanguageMode.NEPALI_DEVANAGARI,
                    LanguageMode.NEPALI_ROMANIZED,
                    -> "यो तस्बिरको वर्णन गर्नुहोस्।"
                    else -> "Describe this image."
                }
                attachment != null -> "What's in this attachment?"
                else -> ""
            }
        }
        if (text.isNotEmpty()) pending.trySend(IncomingPrompt(text, attachment))
    }

    fun setMode(value: ChatMode) {
        mode.value = value
    }

    fun setWebEnabled(value: Boolean) {
        webEnabled.value = value
    }

    fun newChat() {
        persistCurrent()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            updatedAt = System.currentTimeMillis(),
        )
        _sessions.update { list -> (list + session).sortedByDescending { it.updatedAt } }
        _currentSessionId.value = session.id
        _messages.value = emptyList()
        ChatSessionStore.save(_sessions.value, session.id, sessionMessages)
    }

    fun switchSession(id: String) {
        if (id.isBlank() || id == _currentSessionId.value) return
        if (_sessions.value.none { it.id == id }) return
        persistCurrent()
        _currentSessionId.value = id
        _messages.value = sessionMessages[id].orEmpty()
        ChatSessionStore.save(_sessions.value, id, sessionMessages)
    }

    fun deleteSession(id: String) {
        if (_sessions.value.none { it.id == id }) return
        persistCurrent()
        sessionMessages.remove(id)
        val remaining = _sessions.value.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = "New chat",
                updatedAt = System.currentTimeMillis(),
            )
            _sessions.value = listOf(session)
            _currentSessionId.value = session.id
            _messages.value = emptyList()
        } else {
            _sessions.value = remaining
            if (_currentSessionId.value == id) {
                val next = remaining.maxByOrNull { it.updatedAt } ?: remaining.first()
                _currentSessionId.value = next.id
                _messages.value = sessionMessages[next.id].orEmpty()
            }
        }
        ChatSessionStore.save(_sessions.value, _currentSessionId.value, sessionMessages)
    }

    fun messageCount(sessionId: String): Int =
        if (sessionId == _currentSessionId.value) {
            _messages.value.size
        } else {
            sessionMessages[sessionId].orEmpty().size
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
                sessionId = _currentSessionId.value,
            )
        }
        persistCurrent()
    }

    fun stop() {
        val agent = agentJob
        if (agent != null && agent.isActive) {
            agent.cancel()
            return
        }
        ModelManager.stopChat()
    }

    private suspend fun generate(prompt: String, attachment: ChatAttachment? = null) {
        _generating.value = true
        val assistantId = nextId++
        _messages.update {
            it + Message(
                assistantId,
                ChatRole.ASSISTANT,
                "",
                streaming = true,
                sessionId = _currentSessionId.value,
            )
        }

        try {
            val raw = prompt.trim()
            val explicitWeb = raw.startsWith("/web ", ignoreCase = true)
            val chatPrompt = if (explicitWeb) raw.substring(5).trim() else raw
            val cleaned = HybridRouter.clean(chatPrompt)
            val route = HybridRouter.route(if (explicitWeb) chatPrompt else raw)
            val useWeb = route != HybridRoute.CLOUD && (explicitWeb || webEnabled.value)

            val attachmentBlock = attachment
                ?.takeIf { it.text.isNotBlank() }
                ?.let {
                    "Attached file: ${it.name} (${it.kind.label}).\n" +
                        "${it.text}\n" +
                        "Use the attached content to answer the user's question."
                }

            var webBlock: String? = null
            if (useWeb) {
                replace(assistantId, "Searching web...")
                val results = WebSearchClient.search(cleaned, WebSearchSettings.apiKey())
                if (results.isNotEmpty()) {
                    webBlock = WebSearchClient.formatForPrompt(results)
                    setWebSources(assistantId, results)
                }
                replace(assistantId, "")
            }

            when {
                attachment?.imagePath != null -> {
                    generateLocal(
                        cleaned, assistantId, webBlock, attachmentBlock,
                        attachment.imagePath,
                    )
                }

                route == HybridRoute.CLOUD -> {
                    runCloudAction(cleaned, assistantId)
                }

                mode.value == ChatMode.CLOUD -> {
                    generateCloudChat(cleaned, assistantId, webBlock, attachmentBlock)
                }

                mode.value == ChatMode.LOCAL -> {
                    generateLocal(
                        cleaned, assistantId, webBlock, attachmentBlock,
                        attachment?.imagePath,
                    )
                }

                ModelManager.chatStatus.value is ModelStatus.Ready -> {
                    generateLocal(
                        cleaned, assistantId, webBlock, attachmentBlock,
                        attachment?.imagePath,
                    )
                }

                CloudSettings.hasKey() -> {
                    generateCloudChat(cleaned, assistantId, webBlock, attachmentBlock)
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
            persistCurrent()
            _generating.value = false
        }
    }

    private suspend fun generateLocal(
        prompt: String,
        assistantId: Long,
        webBlock: String? = null,
        attachmentBlock: String? = null,
        imagePath: String? = null,
    ) {
        if (imagePath != null && ModelManager.chatBackend.value != ChatBackend.LITERT_LM) {
            replace(
                assistantId,
                "Image input needs Gemma 4 E2B (.litertlm). Load it in Models to use vision.",
            )
            return
        }
        if (ModelManager.chatStatus.value !is ModelStatus.Ready) {
            replace(
                assistantId,
                "No chat model loaded. Open Models, choose a .gguf or .litertlm file and tap Load.",
            )
            return
        }

        val system = buildSystemPrompt(
            prompt,
            webBlock = webBlock,
            attachmentBlock = attachmentBlock,
        )
        val turns = _messages.value
            .filter { it.id != assistantId && it.text.isNotBlank() }
            .takeLast(8)
            .map { ChatTurn(it.role, it.text) }

        val speech = if (shouldSpeak()) SentenceSpeechBuffer() else null
        ModelManager.generateChat(system, turns, imagePath).collect { piece ->
            append(assistantId, piece)
            speech?.append(piece)
        }
        speech?.flush()

        if (textOf(assistantId).isBlank()) {
            replace(assistantId, "The model returned no text.")
        }
    }

    private suspend fun generateCloudChat(
        prompt: String,
        assistantId: Long,
        webBlock: String? = null,
        attachmentBlock: String? = null,
    ) {
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

        val system = buildSystemPrompt(
            prompt,
            local = false,
            webBlock = webBlock,
            attachmentBlock = attachmentBlock,
        )
        val turns = _messages.value
            .filter { it.id != assistantId && it.text.isNotBlank() }
            .takeLast(8)
            .map { ChatTurn(it.role, it.text) }

        CloudLlmClient.chatCompletion(turns, system).fold(
            onSuccess = { answer ->
                val cleanAnswer = answer.trim().ifBlank { "The cloud model returned no text." }
                replace(assistantId, cleanAnswer)
                if (shouldSpeak()) {
                    SentenceSpeechBuffer().apply {
                        append(cleanAnswer)
                        flush()
                    }
                }
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
                "Enable Mero Agent Accessibility in Settings so I can read and tap the screen.",
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
            speakAgentSummary(stopped)
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
            speakAgentSummary(result)
        }

        scope.launch {
            delay(2000)
            IslandAgent.clearIfFinished()
        }
    }

    private suspend fun buildSystemPrompt(
        query: String,
        local: Boolean = true,
        webBlock: String? = null,
        attachmentBlock: String? = null,
    ): String {
        val memories = retrieveMemories(query)
        val modelName = ModelManager.chatSelection.value?.displayName
        return buildString {
            if (local) {
                if (modelName != null) {
                    append("You are Mero Agent, a private on-device AI assistant running locally via $modelName.")
                } else {
                    append("You are Mero Agent, a private on-device AI assistant running locally.")
                }
            } else {
                append("You are Mero Agent, a helpful AI assistant with access to the user's memories.")
            }
            append(" ")
            append(LanguageSettings.promptDirective(query))
            append(" Answer directly and concisely.")
            if (memories.isNotEmpty()) {
                Log.i(
                    "MeroRag",
                    "Injecting ${memories.size} memory snippets into prompt; sources=" +
                        memories.joinToString(",") { it.source },
                )
                append(
                    "\n\nThe following are OCR transcripts and notes from the user's device memory. " +
                        "Use them directly to answer questions about screenshots, notifications, " +
                        "or saved notes. The OCR transcripts are already included below; do not " +
                        "ask the user to provide the screenshot or more context. When the user " +
                        "asks about a screenshot, quote at least one relevant OCR line explicitly " +
                        "in your answer.\n",
                )
                memories.forEach { memory ->
                    append("- [")
                    append(memory.source.ifBlank { "memory" })
                    append(" | ")
                    append(formatMemoryTimestamp(memory.timestamp))
                    append("] ")
                    if (memory.title.isNotBlank()) append(memory.title).append(": ")
                    append(memory.content.replace('\n', ' ').take(600))
                    append('\n')
                }
            }
            if (webBlock != null) {
                append("\n\n")
                append(webBlock)
                append(
                    "\nAnswer the user's question concisely using the live results above. " +
                        "Do not say you lack internet access.",
                )
            }
            if (attachmentBlock != null) {
                append("\n\n")
                append(attachmentBlock)
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

    private fun formatMemoryTimestamp(timestamp: Long): String =
        MEMORY_TIME_FORMATTER.format(
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()),
        )

    private suspend fun recordAction(goal: String, summary: String, steps: List<String>) {
        withContext(Dispatchers.IO) {
            repository.insert(
                source = MemorySource.ACTION,
                packageName = "Mero Agent",
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

    private fun shouldSpeak(): Boolean =
        (VoiceOutputManager.enabled.value || VoiceChatSession.active.value) &&
            !VoiceChatSession.muted.value

    private fun speakAgentSummary(result: UiAgentResult) {
        if (!shouldSpeak()) return
        val line = result.details.lastOrNull()?.detail
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank {
                result.message.lineSequence().firstOrNull()?.trim().orEmpty()
            }
        if (line.isNotBlank()) {
            VoiceOutputManager.enqueueSentence(line.take(180))
        }
    }

    private class SentenceSpeechBuffer {
        private val buffer = StringBuilder()

        fun append(text: String) {
            buffer.append(text)
            while (true) {
                val boundary = firstBoundary()
                if (boundary < 0) return
                val sentence = buffer.substring(0, boundary + 1).trim()
                buffer.delete(0, boundary + 1)
                if (sentence.length >= 2) VoiceOutputManager.enqueueSentence(sentence)
            }
        }

        fun flush() {
            val remainder = buffer.toString().trim()
            buffer.clear()
            if (remainder.isNotEmpty()) VoiceOutputManager.enqueueSentence(remainder)
        }

        private fun firstBoundary(): Int {
            for (index in buffer.indices) {
                if (buffer[index] in charArrayOf('.', '!', '?', '\n', '\u0964', '\u0965')) return index
            }
            return -1
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

    private fun initializeSessions() {
        ChatSessionStore.init(appContext)
        val stored = ChatSessionStore.load()
        if (stored.sessions.isEmpty()) {
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = "New chat",
                updatedAt = System.currentTimeMillis(),
            )
            _sessions.value = listOf(session)
            _currentSessionId.value = session.id
            if (_messages.value.isNotEmpty()) {
                sessionMessages[session.id] = _messages.value
            }
        } else {
            _sessions.value = stored.sessions.sortedByDescending { it.updatedAt }
            val current = stored.currentSessionId
                ?.takeIf { id -> stored.sessions.any { it.id == id } }
                ?: stored.sessions.maxByOrNull { it.updatedAt }!!.id
            _currentSessionId.value = current
            sessionMessages.putAll(stored.messages)
            _messages.value = sessionMessages[current].orEmpty()
        }
        nextId = (
            sessionMessages.values.flatten().maxOfOrNull { it.id }
                ?: _messages.value.maxOfOrNull { it.id }
                ?: -1L
            ) + 1
        persistCurrent()
    }

    private fun autoTitle(text: String) {
        val current = _sessions.value.firstOrNull { it.id == _currentSessionId.value } ?: return
        if (current.title != "New chat") return
        val title = text.trim().replace('\n', ' ').take(36).ifBlank { "New chat" }
        _sessions.update { list ->
            list.map { if (it.id == current.id) it.copy(title = title) else it }
                .sortedByDescending { it.updatedAt }
        }
    }

    private fun persistCurrent() {
        val current = _currentSessionId.value
        if (current.isBlank()) return
        sessionMessages[current] = _messages.value
        _sessions.update { list ->
            list.map {
                if (it.id == current) it.copy(updatedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.updatedAt }
        }
        ChatSessionStore.save(_sessions.value, current, sessionMessages)
    }

    private fun setWebSources(id: Long, sources: List<WebResult>) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(webSources = sources) else it }
        }
    }

    private fun textOf(id: Long): String =
        _messages.value.firstOrNull { it.id == id }?.text.orEmpty()
}
