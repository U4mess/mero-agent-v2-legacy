package com.u4mess.ume.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared state for the conversational Talk Mode overlay. */
object VoiceChatSession {
    val active = MutableStateFlow(false)
    val muted = MutableStateFlow(false)

    private val _openRequests = MutableStateFlow(0L)
    val openRequests: StateFlow<Long> = _openRequests.asStateFlow()

    fun requestOpen() {
        _openRequests.value += 1
    }

    fun consumeOpenRequest() {
        _openRequests.value = 0
    }

    fun setActive(value: Boolean) {
        active.value = value
    }

    fun setMuted(value: Boolean) {
        muted.value = value
    }
}
