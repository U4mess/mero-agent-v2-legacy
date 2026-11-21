package com.u4mess.ume.brain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.SharedPreferences
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

enum class ModelRole { CHAT, EMBEDDING }

object ModelManager {

    data class Selection(val uri: String, val displayName: String, val size: Long)

    private const val PREFS_NAME = "ume_models"
    private const val CHAT = "chat"
    private const val EMBEDDING = "embedding"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    val chatSelection = MutableStateFlow<Selection?>(null)
    val embeddingSelection = MutableStateFlow<Selection?>(null)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        chatSelection.value = read(CHAT)
        embeddingSelection.value = read(EMBEDDING)
    }

    fun select(context: Context, role: ModelRole, uri: Uri): Boolean {
        val selection = describe(context, uri) ?: return false
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        when (role) {
            ModelRole.CHAT -> {
                chatSelection.value = selection
                write(CHAT, selection)
            }

            ModelRole.EMBEDDING -> {
                embeddingSelection.value = selection
                write(EMBEDDING, selection)
            }
        }
        return true
    }

    suspend fun loadChat(): Boolean = withContext(Dispatchers.IO) {
        val selection = chatSelection.value ?: return@withContext false
        val opened = open(selection) ?: return@withContext false
        opened.use { LocalLlmEngine.loadChat(it.path) }
    }

    suspend fun loadEmbedding(): Boolean = withContext(Dispatchers.IO) {
        val selection = embeddingSelection.value ?: return@withContext false
        val opened = open(selection) ?: return@withContext false
        opened.use { LocalLlmEngine.loadEmbedding(it.path) }
    }

    suspend fun ensureChatLoaded(): Boolean =
        LocalLlmEngine.chatStatus.value is ModelStatus.Ready || loadChat()

    private fun describe(context: Context, uri: Uri): Selection? {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
        }
        val displayName = name ?: uri.lastPathSegment ?: return null
        if (!displayName.endsWith(".gguf", ignoreCase = true)) return null
        return Selection(uri.toString(), displayName, size)
    }

    private fun open(selection: Selection): OpenedModel? {
        val uri = Uri.parse(selection.uri)
        return when (uri.scheme) {
            "content" -> {
                val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return null
                OpenedModel("/proc/self/fd/${descriptor.fd}", descriptor)
            }

            else -> {
                val path = if (uri.scheme == "file") uri.path else selection.uri
                path?.takeIf { it.isNotBlank() }?.let { OpenedModel(it, null) }
            }
        }
    }

    private fun read(prefix: String): Selection? {
        val uri = prefs.getString("${prefix}_uri", null) ?: return null
        return Selection(
            uri = uri,
            displayName = prefs.getString("${prefix}_name", null) ?: "model.gguf",
            size = prefs.getLong("${prefix}_size", 0L),
        )
    }

    private fun write(prefix: String, selection: Selection) {
        prefs.edit()
            .putString("${prefix}_uri", selection.uri)
            .putString("${prefix}_name", selection.displayName)
            .putLong("${prefix}_size", selection.size)
            .apply()
    }

    private class OpenedModel(val path: String, private val descriptor: Closeable?) : Closeable {
        override fun close() {
            descriptor?.close()
        }
    }
}
