package com.u4mess.ume.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide state shared by the island overlay and the app UI. */
object IslandState {
    val running = MutableStateFlow(false)
}

/** Prompts captured by the island, in the order they were sent. */
object PromptInbox {
    private val _prompts = MutableStateFlow<List<String>>(emptyList())
    val prompts: StateFlow<List<String>> = _prompts.asStateFlow()

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isNotEmpty()) _prompts.value = _prompts.value + prompt
    }
}

data class AgentStatus(val text: String, val active: Boolean)

/** Live status shown in the island while the UI agent is working. */
object IslandAgent {
    private val _status = MutableStateFlow<AgentStatus?>(null)
    val status: StateFlow<AgentStatus?> = _status.asStateFlow()

    @Volatile
    private var stopHandler: (() -> Unit)? = null

    fun setStopHandler(handler: (() -> Unit)?) {
        stopHandler = handler
    }

    fun update(text: String, active: Boolean) {
        _status.value = AgentStatus(text, active)
    }

    fun finish(text: String) {
        _status.value = AgentStatus(text, false)
    }

    fun stop() {
        stopHandler?.invoke()
    }

    fun clearIfFinished() {
        if (_status.value?.active != true) _status.value = null
    }
}
