package com.u4mess.ume.service

import kotlinx.coroutines.CompletableDeferred
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

data class ApprovalRequest(val id: Long, val prompt: String)

/** Live status shown in the island while the UI agent is working. */
object IslandAgent {
    private val _status = MutableStateFlow<AgentStatus?>(null)
    val status: StateFlow<AgentStatus?> = _status.asStateFlow()

    private val _approval = MutableStateFlow<ApprovalRequest?>(null)
    val approval: StateFlow<ApprovalRequest?> = _approval.asStateFlow()

    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var nextApprovalId = 0L

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

    suspend fun requestApproval(prompt: String): Boolean {
        val request = ApprovalRequest(nextApprovalId++, prompt)
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _approval.value = request
        update("Approve: $prompt", active = true)
        return try {
            deferred.await()
        } finally {
            if (_approval.value?.id == request.id) _approval.value = null
            if (approvalDeferred === deferred) approvalDeferred = null
        }
    }

    fun approve() {
        approvalDeferred?.complete(true)
    }

    fun cancelApproval() {
        approvalDeferred?.complete(false)
    }

    fun clearIfFinished() {
        if (_status.value?.active != true) {
            _status.value = null
            _approval.value = null
        }
    }
}
