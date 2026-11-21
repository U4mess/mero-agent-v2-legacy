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
