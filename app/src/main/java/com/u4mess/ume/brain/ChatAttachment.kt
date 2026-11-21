package com.u4mess.ume.brain

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.u4mess.ume.sensors.MediaDocumentIndexer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

const val MAX_ATTACHMENT_CHARS = 6_000

enum class ChatAttachmentKind(val label: String) {
    PDF("PDF"),
    IMAGE("IMAGE"),
    TEXT("TEXT"),
}

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val kind: ChatAttachmentKind,
    val text: String,
)

object ChatAttachmentStore {
    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText.asStateFlow()

    private val _sharedAttachment = MutableStateFlow<ChatAttachment?>(null)
    val sharedAttachment: StateFlow<ChatAttachment?> = _sharedAttachment.asStateFlow()

    fun stageSharedText(text: String) {
        if (text.isNotBlank()) _sharedText.value = text.trim()
    }

    fun consumeSharedText() {
        _sharedText.value = null
    }

    fun stageSharedAttachment(attachment: ChatAttachment) {
        _sharedAttachment.value = attachment
    }

    fun consumeSharedAttachment() {
        _sharedAttachment.value = null
    }
}

object ChatAttachmentExtractor {
    suspend fun extract(context: Context, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = queryName(context, uri) ?: uri.lastPathSegment.orEmpty()
            .ifBlank { "Attachment" }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?: mimeFromName(name)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        val kind = when {
            mime == "application/pdf" || extension == "pdf" -> ChatAttachmentKind.PDF
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> ChatAttachmentKind.IMAGE
            else -> ChatAttachmentKind.TEXT
        }
        val text = when (kind) {
            ChatAttachmentKind.PDF, ChatAttachmentKind.IMAGE ->
                MediaDocumentIndexer.extractText(uri, mime)
            ChatAttachmentKind.TEXT -> runCatching {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
        }.trim().take(MAX_ATTACHMENT_CHARS)

        ChatAttachment(name = name, mimeType = mime, kind = kind, text = text)
    }

    private fun queryName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> "text/plain"
    }

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
}
