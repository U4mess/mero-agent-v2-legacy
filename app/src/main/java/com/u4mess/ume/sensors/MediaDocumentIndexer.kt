package com.u4mess.ume.sensors

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.u4mess.ume.brain.LocalLlmEngine
import com.u4mess.ume.brain.ModelStatus
import com.u4mess.ume.memory.MemoryRepository
import com.u4mess.ume.memory.MemorySource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Indexes screenshots and PDFs through MediaStore observers or explicit SAF imports. */
object MediaDocumentIndexer {

    private const val PREFS_NAME = "ume_media_index"
    private const val KEY_ENABLED = "auto_index_enabled"
    private const val KEY_INDEXED = "indexed_uris"
    private const val MAX_INDEXED = 500
    private const val DEBOUNCE_MS = 1_500L
    private const val RECENT_WINDOW_MS = 15L * 60L * 1000L
    private const val MAX_PDF_PAGES = 15
    private const val MAX_PDF_CHARS = 8_000

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: MemoryRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pdfInitialized = AtomicBoolean(false)
    private var registered = false

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val scanRunnable = Runnable {
        scope.launch {
            scanScreenshots()
            scanPdfs()
        }
    }

    private val imageObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleScan()
        }
    }

    private val fileObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleScan()
        }
    }

    fun init(context: Context, memoryRepository: MemoryRepository) {
        appContext = context.applicationContext
        repository = memoryRepository
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
        if (_enabled.value && hasMediaPermission(appContext)) {
            startObservers()
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) {
            startObservers()
        } else {
            stopObservers()
        }
    }

    fun refresh(context: Context) {
        if (_enabled.value && hasMediaPermission(context)) {
            startObservers()
        } else {
            stopObservers()
        }
    }

    fun hasMediaPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Reusable OCR/PDF extraction for Chat attachments. */
    suspend fun extractText(uri: Uri, mimeType: String? = null): String = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext ""
        val name = queryDisplayName(appContext.contentResolver, uri)
            ?: uri.lastPathSegment.orEmpty()
        val type = mimeType ?: runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        val extension = name.substringAfterLast('.', "").lowercase()
        val isPdf = type == "application/pdf" || extension == "pdf"
        val isImage = type?.startsWith("image/") == true ||
            extension in setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
        runCatching {
            when {
                isPdf -> extractPdfText(uri)
                isImage -> extractImageText(uri)
                else -> ""
            }
        }.getOrElse { throwable ->
            Log.e("UmeMediaIndex", "Failed to extract attachment $uri", throwable)
            ""
        }
    }

    fun displayName(uri: Uri): String =
        if (::appContext.isInitialized) {
            queryDisplayName(appContext.contentResolver, uri) ?: uri.lastPathSegment.orEmpty()
        } else {
            uri.lastPathSegment.orEmpty()
        }

    /**
     * Runs the same OCR/PDF pipeline for an explicitly imported SAF document.
     * Returns true only when a memory was actually inserted.
     */
    suspend fun indexUri(uri: Uri, mimeType: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext false

        val resolver = appContext.contentResolver
        val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment.orEmpty()
        val type = mimeType ?: runCatching { resolver.getType(uri) }.getOrNull()
        val extension = name.substringAfterLast('.', "").lowercase()
        val isPdf = type == "application/pdf" || extension == "pdf"
        val isImage = type?.startsWith("image/") == true ||
            extension in setOf("png", "jpg", "jpeg", "webp", "heic", "heif")

        if (!isPdf && !isImage) return@withContext false
        if (isIndexed(uri)) return@withContext false

        val text = try {
            if (isPdf) extractPdfText(uri) else extractImageText(uri)
        } catch (throwable: Throwable) {
            Log.e("UmeMediaIndex", "Failed to extract $uri", throwable)
            null
        }.orEmpty().trim()

        if (text.isBlank() || (isImage && text.length < 15)) {
            markIndexed(uri)
            return@withContext false
        }

        val title = name.ifBlank { if (isPdf) "PDF Document" else "Screenshot" }
        return@withContext runCatching {
            val id = repository.insert(
                source = if (isPdf) MemorySource.PDF else MemorySource.SCREENSHOT,
                packageName = if (isPdf) "PDF Document" else "Screenshot OCR",
                title = title,
                content = text,
            )
            if (LocalLlmEngine.embeddingStatus.value is ModelStatus.Ready) {
                LocalLlmEngine.embed("$title\n$text")?.let { repository.updateEmbedding(id, it) }
            }
            markIndexed(uri)
            true
        }.getOrElse { throwable ->
            Log.e("UmeMediaIndex", "Failed to save $uri", throwable)
            false
        }
    }

    private fun startObservers() {
        if (registered || !hasMediaPermission(appContext)) return
        val resolver = appContext.contentResolver
        var any = false
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                imageObserver,
            )
            any = true
        }.onFailure { Log.e("UmeMediaIndex", "Image observer failed", it) }

        runCatching {
            resolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                fileObserver,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.registerContentObserver(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    true,
                    fileObserver,
                )
            }
            any = true
        }.onFailure { Log.e("UmeMediaIndex", "File observer failed", it) }

        registered = any
        if (any) Log.i("UmeMediaIndex", "Media observers registered")
    }

    private fun stopObservers() {
        if (!registered) return
        runCatching { appContext.contentResolver.unregisterContentObserver(imageObserver) }
        runCatching { appContext.contentResolver.unregisterContentObserver(fileObserver) }
        mainHandler.removeCallbacks(scanRunnable)
        scope.coroutineContext.cancelChildren()
        registered = false
    }

    private fun scheduleScan() {
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, DEBOUNCE_MS)
    }

    private suspend fun scanScreenshots() {
        if (!hasMediaPermission(appContext)) return
        val since = (System.currentTimeMillis() - RECENT_WINDOW_MS) / 1000L
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.IS_PENDING,
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND " +
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        val cursor = runCatching {
            appContext.contentResolver.query(
                collection,
                projection,
                selection,
                arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathIndex = it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val bucketIndex = it.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            var processed = 0
            while (it.moveToNext() && processed < 20) {
                val path = if (pathIndex >= 0) it.getString(pathIndex).orEmpty() else ""
                val bucket = if (bucketIndex >= 0) it.getString(bucketIndex).orEmpty() else ""
                val isScreenshot = path.contains("screenshot", ignoreCase = true) ||
                    bucket.contains("screenshot", ignoreCase = true)
                if (!isScreenshot) continue
                val id = it.getLong(idIndex)
                val uri = ContentUris.withAppendedId(collection, id)
                if (!isIndexed(uri)) {
                    indexUri(uri, "image/*")
                    processed++
                }
            }
        }
    }

    private suspend fun scanPdfs() {
        if (!hasMediaPermission(appContext)) return
        val since = (System.currentTimeMillis() - RECENT_WINDOW_MS) / 1000L
        scanPdfCollection(MediaStore.Files.getContentUri("external"), since)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scanPdfCollection(MediaStore.Downloads.EXTERNAL_CONTENT_URI, since)
        }
    }

    private suspend fun scanPdfCollection(collection: Uri, since: Long) {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? AND " +
            "${MediaStore.Files.FileColumns.DATE_ADDED} >= ?"
        val cursor = runCatching {
            appContext.contentResolver.query(
                collection,
                projection,
                selection,
                arrayOf("application/pdf", since.toString()),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return

        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            var processed = 0
            while (it.moveToNext() && processed < 20) {
                val uri = ContentUris.withAppendedId(collection, it.getLong(idIndex))
                if (!isIndexed(uri)) {
                    indexUri(uri, "application/pdf")
                    processed++
                }
            }
        }
    }

    private suspend fun extractImageText(uri: Uri): String {
        val image = InputImage.fromFilePath(appContext, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result -> continuation.resume(result.text) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun extractPdfText(uri: Uri): String {
        ensurePdfInitialized()
        val stream = appContext.contentResolver.openInputStream(uri) ?: return ""
        return stream.use { input ->
            PDDocument.load(input).use { document ->
                val pages = minOf(document.numberOfPages, MAX_PDF_PAGES)
                if (pages <= 0) return@use ""
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = pages
                }
                stripper.getText(document).trim().take(MAX_PDF_CHARS)
            }
        }
    }

    private fun ensurePdfInitialized() {
        if (pdfInitialized.compareAndSet(false, true)) {
            runCatching { PDFBoxResourceLoader.init(appContext) }
                .onFailure { Log.e("UmeMediaIndex", "PDFBox init failed", it) }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()

    private fun isIndexed(uri: Uri): Boolean =
        prefs.getStringSet(KEY_INDEXED, emptySet()).orEmpty().contains(uri.toString())

    private fun markIndexed(uri: Uri) {
        val indexed = prefs.getStringSet(KEY_INDEXED, emptySet()).orEmpty().toMutableSet()
        indexed.add(uri.toString())
        while (indexed.size > MAX_INDEXED) {
            indexed.remove(indexed.first())
        }
        prefs.edit().putStringSet(KEY_INDEXED, indexed).apply()
    }
}
