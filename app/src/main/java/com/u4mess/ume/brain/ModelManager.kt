package com.u4mess.ume.brain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.SharedPreferences
import android.util.Log
import com.u4mess.ume.UmeApplication
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelRole { CHAT, EMBEDDING }

enum class ChatBackend(val label: String) {
    LLAMA_CPP("llama.cpp"),
    LITERT_LM("LiteRT-LM"),
}

object ModelManager {

    data class Selection(val uri: String, val displayName: String, val size: Long)

    private const val PREFS_NAME = "ume_models"
    private const val CHAT = "chat"
    private const val EMBEDDING = "embedding"
    private const val AUTO_LOAD = "auto_load_models"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val backfillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backfilling = AtomicBoolean(false)

    val chatSelection = MutableStateFlow<Selection?>(null)
    val embeddingSelection = MutableStateFlow<Selection?>(null)
    val chatStatus = MutableStateFlow<ModelStatus>(ModelStatus.Unloaded)
    val chatBackend = MutableStateFlow<ChatBackend?>(null)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        chatSelection.value = read(CHAT)
        embeddingSelection.value = read(EMBEDDING)
    }

    fun autoLoadEnabled(): Boolean = prefs.getBoolean(AUTO_LOAD, true)

    fun setAutoLoad(value: Boolean) {
        prefs.edit().putBoolean(AUTO_LOAD, value).apply()
    }

    fun select(context: Context, role: ModelRole, uri: Uri): Boolean {
        val selection = describe(context, uri, role) ?: return false
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
        chatStatus.value = ModelStatus.Loading
        if (selection.displayName.endsWith(".litertlm", ignoreCase = true)) {
            LocalLlmEngine.unloadChat()
            val path = runCatching { materializeLiteRtlm(selection) }.getOrNull()
            if (path == null) {
                chatStatus.value = ModelStatus.Error("Could not copy .litertlm model")
                chatBackend.value = null
                return@withContext false
            }
            val updated = selection.copy(uri = path)
            chatSelection.value = updated
            write(CHAT, updated)
            val loaded = LiteRtLmEngine.load(path, appContext.cacheDir.absolutePath)
            if (loaded) {
                chatBackend.value = ChatBackend.LITERT_LM
                chatStatus.value = ModelStatus.Ready
                setAutoLoad(true)
            } else {
                chatBackend.value = null
                chatStatus.value = ModelStatus.Error(
                    "Could not load .litertlm model",
                )
            }
            return@withContext loaded
        }

        LiteRtLmEngine.unload()
        val opened = open(selection)
        if (opened == null) {
            chatStatus.value = ModelStatus.Error("Could not open model file")
            chatBackend.value = null
            return@withContext false
        }
        val loaded = opened.use { LocalLlmEngine.loadChat(it.path) }
        if (loaded) {
            chatBackend.value = ChatBackend.LLAMA_CPP
            chatStatus.value = ModelStatus.Ready
            setAutoLoad(true)
        } else {
            chatBackend.value = null
            chatStatus.value = LocalLlmEngine.chatStatus.value
        }
        loaded
    }

    suspend fun unloadChat() = withContext(Dispatchers.IO) {
        LiteRtLmEngine.unload()
        LocalLlmEngine.unloadChat()
        chatBackend.value = null
        chatStatus.value = ModelStatus.Unloaded
    }

    fun generateChat(
        system: String,
        turns: List<ChatTurn>,
        imagePath: String? = null,
    ): Flow<String> = when (chatBackend.value) {
        ChatBackend.LITERT_LM -> LiteRtLmEngine.generate(system, turns, imagePath)
        ChatBackend.LLAMA_CPP -> LocalLlmEngine.generate(
            LocalLlmEngine.formatPrompt(system, turns),
        )
        null -> flow { error("No local chat model is loaded") }
    }

    fun stopChat() {
        LiteRtLmEngine.cancel()
        LocalLlmEngine.stopGeneration()
    }

    private fun backfillMissingEmbeddings() {
        if (!backfilling.compareAndSet(false, true)) return
        backfillScope.launch {
            try {
                val repository = (appContext as? UmeApplication)?.memoryRepository ?: return@launch
                val missing = repository.all().filter {
                    it.embedding == null || it.embedding?.size != 384
                }
                var generated = 0
                for (memory in missing) {
                    if (LocalLlmEngine.embeddingStatus.value !is ModelStatus.Ready) break
                    val text = buildString {
                        if (memory.title.isNotBlank()) append(memory.title).append('\n')
                        append(memory.content)
                    }.take(4000)
                    val vector = LocalLlmEngine.embed(text) ?: continue
                    repository.updateEmbedding(memory.id, vector)
                    generated++
                }
                Log.i(
                    "MeroRag",
                    "Embedding backfill complete: generated=$generated missing=${missing.size}",
                )
            } finally {
                backfilling.set(false)
            }
        }
    }

    private fun materializeLiteRtlm(selection: Selection): String? {
        val uri = Uri.parse(selection.uri)
        if (uri.scheme.isNullOrBlank()) {
            return selection.uri.takeIf { File(it).isFile }
        }
        if (uri.scheme == "file") {
            return uri.path?.takeIf { File(it).isFile }
        }
        if (uri.scheme != "content") return null
        val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
        val safeName = selection.displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(modelsDir, safeName.ifBlank { "model.litertlm" })
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return destination.absolutePath
    }

    suspend fun loadEmbedding(): Boolean = withContext(Dispatchers.IO) {
        val selection = embeddingSelection.value ?: return@withContext false
        LocalLlmEngine.embeddingStatus.value = ModelStatus.Loading
        val path = runCatching { materializeGguf(selection) }.getOrNull()
        if (path == null) {
            LocalLlmEngine.embeddingStatus.value =
                ModelStatus.Error("Could not copy embedding model into app storage")
            return@withContext false
        }
        if (path != selection.uri) {
            val updated = selection.copy(uri = path)
            embeddingSelection.value = updated
            write(EMBEDDING, updated)
        }
        val loaded = LocalLlmEngine.loadEmbedding(path)
        if (loaded) {
            setAutoLoad(true)
            backfillMissingEmbeddings()
        }
        loaded
    }

    private fun materializeGguf(selection: Selection): String? {
        val uri = Uri.parse(selection.uri)
        if (uri.scheme.isNullOrBlank()) {
            return selection.uri.takeIf { File(it).isFile }
        }
        if (uri.scheme == "file") {
            return uri.path?.takeIf { File(it).isFile }
        }
        if (uri.scheme != "content") return null
        val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
        val safeName = selection.displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "embedding.gguf" }
        val destination = File(modelsDir, safeName)
        if (destination.isFile && selection.size > 0 &&
            destination.length() == selection.size
        ) {
            return destination.absolutePath
        }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return destination.absolutePath
    }

    suspend fun ensureChatLoaded(): Boolean =
        chatStatus.value is ModelStatus.Ready || loadChat()

    private fun describe(context: Context, uri: Uri, role: ModelRole): Selection? {
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
        val isGguf = displayName.endsWith(".gguf", ignoreCase = true)
        val isLiteRt = displayName.endsWith(".litertlm", ignoreCase = true)
        val allowed = when (role) {
            ModelRole.CHAT -> isGguf || isLiteRt
            ModelRole.EMBEDDING -> isGguf
        }
        if (!allowed) return null
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
