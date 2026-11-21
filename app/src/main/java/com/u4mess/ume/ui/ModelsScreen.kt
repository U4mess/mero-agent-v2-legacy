package com.u4mess.ume.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.brain.ChatBackend
import com.u4mess.ume.brain.LocalLlmEngine
import com.u4mess.ume.brain.ModelManager
import com.u4mess.ume.brain.ModelRole
import com.u4mess.ume.brain.ModelStatus
import com.u4mess.ume.ui.components.UmeCard
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatSelection by ModelManager.chatSelection.collectAsState()
    val embeddingSelection by ModelManager.embeddingSelection.collectAsState()
    val chatStatus by ModelManager.chatStatus.collectAsState()
    val chatBackend by ModelManager.chatBackend.collectAsState()
    val embeddingStatus by LocalLlmEngine.embeddingStatus.collectAsState()

    val chatPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !ModelManager.select(context, ModelRole.CHAT, uri)) {
            Toast.makeText(context, "Pick a .gguf or .litertlm file", Toast.LENGTH_SHORT).show()
        }
    }
    val embeddingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !ModelManager.select(context, ModelRole.EMBEDDING, uri)) {
            Toast.makeText(context, "Pick a 384-d .gguf file", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Models")

        ModelCard(
            title = "Chat model",
            hint = "Qwen, Llama 3 or Gemma (.gguf / .litertlm)",
            selection = chatSelection,
            status = chatStatus,
            backend = chatBackend,
            onChoose = { chatPicker.launch(arrayOf("*/*")) },
            onLoad = {
                scope.launch {
                    if (!ModelManager.loadChat()) {
                        Toast.makeText(context, "Could not load chat model", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onUnload = { scope.launch { ModelManager.unloadChat() } },
        )

        Spacer(Modifier.height(12.dp))

        ModelCard(
            title = "Embedding model",
            hint = "Optional \u00B7 384-d .gguf (MiniLM, bge-small)",
            selection = embeddingSelection,
            status = embeddingStatus,
            onChoose = { embeddingPicker.launch(arrayOf("*/*")) },
            onLoad = {
                scope.launch {
                    if (!ModelManager.loadEmbedding()) {
                        Toast.makeText(context, "Could not load embedding model", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onUnload = { scope.launch { LocalLlmEngine.unloadEmbedding() } },
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ModelCard(
    title: String,
    hint: String,
    selection: ModelManager.Selection?,
    status: ModelStatus,
    backend: ChatBackend? = null,
    onChoose: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    val loading = status is ModelStatus.Loading
    val ready = status is ModelStatus.Ready

    UmeCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            StatusDot(status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = statusLabel(status, backend),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = selection?.displayName ?: "No file selected",
            color = if (selection != null) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = selection?.let { "${formatSize(it.size)} \u00B7 $hint" } ?: hint,
            color = TextSecondary,
            fontSize = 11.sp,
        )

        if (status is ModelStatus.Error) {
            Spacer(Modifier.height(4.dp))
            Text(status.message, color = TextSecondary, fontSize = 11.sp)
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Choose file", filled = false, enabled = !loading, onClick = onChoose)
            if (ready) {
                PillButton("Unload", filled = false, enabled = true, onClick = onUnload)
            } else {
                PillButton(
                    label = "Load",
                    filled = true,
                    enabled = selection != null && !loading,
                    onClick = onLoad,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: ModelStatus) {
    val color = when (status) {
        ModelStatus.Ready -> TextPrimary
        ModelStatus.Loading -> TextSecondary
        is ModelStatus.Error -> TextSecondary
        ModelStatus.Unloaded -> Border
    }
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun PillButton(label: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val background = when {
        !enabled -> Border
        filled -> TextPrimary
        else -> Elevated
    }
    val foreground = when {
        !enabled -> TextSecondary
        filled -> Black
        else -> TextPrimary
    }

    Text(
        text = label,
        color = foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, if (filled && enabled) TextPrimary else Border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun statusLabel(status: ModelStatus, backend: ChatBackend? = null): String = when (status) {
    ModelStatus.Unloaded -> "Unloaded"
    ModelStatus.Loading -> "Loading..."
    ModelStatus.Ready -> backend?.let { "Loaded \u00B7 ${it.label}" } ?: "Loaded"
    is ModelStatus.Error -> "Error"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes > 0 -> String.format(Locale.US, "%d KB", bytes / 1024)
    else -> "Size unknown"
}

