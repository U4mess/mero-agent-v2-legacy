package com.u4mess.ume.memory

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object MemoryBackupManager {

    private const val VERSION = 1
    private const val APP_NAME = "Mero Agent"
    private const val EMBEDDING_DIMENSIONS = 384

    suspend fun exportToUri(
        context: Context,
        repository: MemoryRepository,
        uri: Uri,
    ): Int = withContext(Dispatchers.IO) {
        val memories = repository.all()
        val array = JSONArray()
        memories.forEach { memory ->
            array.put(
                JSONObject()
                    .put("title", memory.title)
                    .put("content", memory.content)
                    .put("source", memory.source)
                    .put("packageName", memory.packageName)
                    .put("timestamp", memory.timestamp)
                    .apply {
                        memory.embedding?.let { embedding ->
                            put("embedding", JSONArray().apply {
                                embedding.forEach { value -> put(value.toDouble()) }
                            })
                        }
                    },
            )
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("app", APP_NAME)
            .put("exportedAt", System.currentTimeMillis())
            .put("memories", array)

        val output = context.contentResolver.openOutputStream(uri)
            ?: return@withContext 0
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(root.toString())
        }
        memories.size
    }

    suspend fun importFromUri(
        context: Context,
        repository: MemoryRepository,
        uri: Uri,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext 0 to 0
        val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext 0 to 0
        if (root.optInt("version") != VERSION || root.optString("app") != APP_NAME) {
            return@withContext 0 to 0
        }
        val array = root.optJSONArray("memories") ?: return@withContext 0 to 0

        val seen = repository.all()
            .mapTo(mutableSetOf()) { it.dedupeKey() }
        var imported = 0
        var skipped = 0

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: run {
                skipped++
                continue
            }
            val title = item.optString("title")
            val content = item.optString("content")
            val timestamp = item.optLong("timestamp")
            val key = "$timestamp|$title|$content"
            if (!seen.add(key)) {
                skipped++
                continue
            }

            val embedding = item.optJSONArray("embedding")?.let { values ->
                if (values.length() != EMBEDDING_DIMENSIONS) {
                    null
                } else {
                    FloatArray(EMBEDDING_DIMENSIONS) { values.optDouble(it, 0.0).toFloat() }
                }
            }
            repository.insert(
                source = item.optString("source"),
                packageName = item.optString("packageName"),
                title = title,
                content = content,
                embedding = embedding,
                timestamp = timestamp,
            )
            imported++
        }
        imported to skipped
    }

    private fun MemoryEntity.dedupeKey(): String = "$timestamp|$title|$content"
}
