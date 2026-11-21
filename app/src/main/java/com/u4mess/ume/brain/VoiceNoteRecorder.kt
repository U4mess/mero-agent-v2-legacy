package com.u4mess.ume.brain

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object VoiceNoteRecorder {

    const val SAMPLE_RATE = 16_000
    const val MAX_DURATION_MS = 30_000L

    private const val CHANNELS = 1
    private const val BITS = 16
    private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * (BITS / 8)
    private const val HEADER_BYTES = 44
    private const val MAX_PCM_BYTES = (MAX_DURATION_MS * BYTES_PER_SECOND / 1000).toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private var writer: Job? = null
    private var file: File? = null
    private var stopRequested = false
    private var cancelled = false

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _result = MutableStateFlow<ChatAttachment?>(null)
    val result: StateFlow<ChatAttachment?> = _result.asStateFlow()

    fun start(context: Context): Boolean {
        if (_recording.value) return false
        val dir = File(context.cacheDir, "audio").apply { mkdirs() }
        val destination = File(dir, "voice_${System.currentTimeMillis()}.wav")
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, 4096)
        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return false
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return false
        }

        if (runCatching { audioRecord.startRecording() }.isFailure) {
            audioRecord.release()
            destination.delete()
            return false
        }

        file = destination
        recorder = audioRecord
        stopRequested = false
        cancelled = false
        _result.value = null
        _elapsedMs.value = 0L
        _recording.value = true

        writer = scope.launch {
            writeWav(audioRecord, destination, bufferSize)
        }
        return true
    }

    fun stop() {
        if (!_recording.value) return
        stopRequested = true
        runCatching { recorder?.stop() }
    }

    fun consumeResult() {
        _result.value = null
    }

    fun cancel() {
        if (!_recording.value) return
        cancelled = true
        stopRequested = true
        runCatching { recorder?.stop() }
        val destination = file
        scope.launch {
            writer?.join()
            destination?.delete()
            _result.value = null
            _recording.value = false
            _elapsedMs.value = 0L
        }
    }

    private suspend fun writeWav(record: AudioRecord, destination: File, bufferSize: Int) {
        var totalBytes = 0
        var peak = 0
        var failure: Throwable? = null
        RandomAccessFile(destination, "rw").use { output ->
            writeHeader(output, 0)
            val buffer = ByteArray(bufferSize)
            try {
                while (!stopRequested && !cancelled && totalBytes < MAX_PCM_BYTES) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    val allowed = minOf(read, MAX_PCM_BYTES - totalBytes)
                    var index = 0
                    while (index + 1 < allowed) {
                        val sample = (buffer[index].toInt() and 0xFF) or
                            (buffer[index + 1].toInt() shl 8)
                        peak = maxOf(peak, kotlin.math.abs(sample))
                        index += 2
                    }
                    output.write(buffer, 0, allowed)
                    totalBytes += allowed
                    _elapsedMs.value = (totalBytes * 1000L / BYTES_PER_SECOND)
                        .coerceAtMost(MAX_DURATION_MS)
                }
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                runCatching { record.stop() }
                record.release()
                if (cancelled) {
                    _result.value = null
                } else {
                    output.seek(4)
                    writeIntLE(output, HEADER_BYTES - 8 + totalBytes)
                    output.seek(40)
                    writeIntLE(output, totalBytes)
                }
            }
        }
        recorder = null
        _recording.value = false
        val durationMs = (totalBytes * 1000L / BYTES_PER_SECOND)
        _elapsedMs.value = durationMs.coerceAtMost(MAX_DURATION_MS)
        if (!cancelled && totalBytes > 0) {
            _result.value = ChatAttachment(
                name = destination.name,
                mimeType = "audio/wav",
                kind = ChatAttachmentKind.AUDIO,
                text = "",
                audioPath = destination.absolutePath,
                audioDurationMs = _elapsedMs.value,
            )
            Log.i(
                "MeroVoice",
                "WAV ready: 16000 Hz mono 16-bit, bytes=$totalBytes peak=$peak",
            )
        } else if (!cancelled) {
            destination.delete()
        }
        if (failure != null) Log.w("MeroVoice", "Recording ended early", failure)
    }

    private fun writeHeader(output: RandomAccessFile, dataBytes: Int) {
        output.writeBytes("RIFF")
        writeIntLE(output, HEADER_BYTES - 8 + dataBytes)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeIntLE(output, 16)
        writeShortLE(output, 1)
        writeShortLE(output, CHANNELS)
        writeIntLE(output, SAMPLE_RATE)
        writeIntLE(output, BYTES_PER_SECOND)
        writeShortLE(output, CHANNELS * (BITS / 8))
        writeShortLE(output, BITS)
        output.writeBytes("data")
        writeIntLE(output, dataBytes)
    }

    private fun writeIntLE(output: RandomAccessFile, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLE(output: RandomAccessFile, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }
}
