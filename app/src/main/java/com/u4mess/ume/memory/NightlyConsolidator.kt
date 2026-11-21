package com.u4mess.ume.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.u4mess.ume.UmeApplication
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ChatRole
import com.u4mess.ume.brain.ChatTurn
import com.u4mess.ume.brain.LocalLlmEngine
import com.u4mess.ume.brain.ModelManager
import com.u4mess.ume.brain.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NightlyConsolidator(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UmeApplication ?: return Result.success()
        val repository = app.memoryRepository
        val since = System.currentTimeMillis() - DAY_MILLIS

        val memories = withContext(Dispatchers.IO) { repository.recentSince(since, limit = 100) }
            .filter { it.source != MemorySource.SUMMARY && it.content.isNotBlank() }
        if (memories.isEmpty()) return Result.success()

        val alreadyLoaded = LocalLlmEngine.chatStatus.value is ModelStatus.Ready
        if (!alreadyLoaded && !ModelManager.loadChat()) return Result.success()

        return try {
            val events = memories.joinToString("\n") { memory ->
                val title = memory.title.ifBlank { memory.source }
                "[${memory.source}] $title: ${memory.content.replace('\n', ' ').take(300)}"
            }.take(MAX_EVENTS_CHARS)

            val prompt = LocalLlmEngine.formatPrompt(
                SYSTEM_PROMPT,
                listOf(ChatTurn(ChatRole.USER, events)),
            )

            val summary = StringBuilder()
            LocalLlmEngine.generate(prompt, maxTokens = MAX_SUMMARY_TOKENS)
                .collect { summary.append(it) }

            val text = summary.toString().trim()
            if (text.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    repository.insert(
                        source = MemorySource.SUMMARY,
                        packageName = "Mero Agent",
                        title = "Daily summary",
                        content = text,
                    )
                }
                ChatController.postProactiveSummary(text)
            }
            Result.success()
        } catch (throwable: Throwable) {
            Result.retry()
        } finally {
            if (!alreadyLoaded) LocalLlmEngine.unloadChat()
        }
    }

    companion object {
        const val WORK_NAME = "nightly-consolidation"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_EVENTS_CHARS = 6000
        private const val MAX_SUMMARY_TOKENS = 384

        private const val SYSTEM_PROMPT =
            "Summarize the events below into a short list of durable facts about the user. " +
                "Keep it under 150 words, use plain language, and omit anything trivial."
    }
}
