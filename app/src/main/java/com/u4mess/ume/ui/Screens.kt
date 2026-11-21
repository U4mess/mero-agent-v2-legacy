package com.u4mess.ume.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.u4mess.ume.UmeApplication
import com.u4mess.ume.brain.LocalLlmEngine
import com.u4mess.ume.brain.ModelStatus
import com.u4mess.ume.memory.MemoryEntity
import com.u4mess.ume.memory.MemorySource
import com.u4mess.ume.memory.NightlyConsolidator
import com.u4mess.ume.sensors.ScreenReaderAccessibilityService
import com.u4mess.ume.service.DynamicIslandService
import com.u4mess.ume.service.IslandState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MemoryScreen() {
    val context = LocalContext.current
    val repository = (context.applicationContext as UmeApplication).memoryRepository
    val connected by ScreenReaderAccessibilityService.connected.collectAsState()
    val revisions by repository.revisions.collectAsState()
    val scope = rememberCoroutineScope()

    var keyword by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }

    LaunchedEffect(keyword, sourceFilter, revisions) {
        memories = withContext(Dispatchers.IO) {
            repository.search(keyword, sourceFilter.ifBlank { null })
        }
    }

    val captureScreen = {
        if (!connected) {
            Toast.makeText(context, "Enable Accessibility to capture the screen", Toast.LENGTH_SHORT).show()
        } else {
            val json = ScreenReaderAccessibilityService.captureUiTreeJson()
            if (json == "[]") {
                Toast.makeText(context, "Nothing to capture", Toast.LENGTH_SHORT).show()
            } else {
                val packageName = ScreenReaderAccessibilityService.currentPackageName().orEmpty()
                scope.launch(Dispatchers.IO) {
                    val id = repository.insert(
                        source = MemorySource.SCREEN,
                        packageName = packageName,
                        title = "Screen capture",
                        content = json,
                    )
                    if (LocalLlmEngine.embeddingStatus.value is ModelStatus.Ready) {
                        LocalLlmEngine.embed("Screen capture $packageName\n${json.take(4000)}")
                            ?.let { repository.updateEmbedding(id, it) }
                    }
                }
                Toast.makeText(context, "Screen captured", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader("Memory") {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Elevated)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .clickable(onClick = captureScreen)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (connected) TextPrimary else TextSecondary),
                )
                Spacer(Modifier.width(7.dp))
                Text("Capture screen", color = TextPrimary, fontSize = 12.sp)
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            MemorySearchField(keyword, onValueChange = { keyword = it })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All", sourceFilter.isEmpty()) { sourceFilter = "" }
                FilterPill("Notifications", sourceFilter == MemorySource.NOTIFICATION) {
                    sourceFilter = MemorySource.NOTIFICATION
                }
                FilterPill("Screen", sourceFilter == MemorySource.SCREEN) {
                    sourceFilter = MemorySource.SCREEN
                }
            }
        }

        if (memories.isEmpty()) {
            val filtered = keyword.isNotBlank() || sourceFilter.isNotEmpty()
            EmptyState(
                title = if (filtered) "No matches" else "Nothing remembered yet",
                body = if (filtered) {
                    "Try a different word or filter."
                } else {
                    "Notifications and screen captures Ume stores will appear here."
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        onDelete = {
                            scope.launch(Dispatchers.IO) { repository.delete(memory.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayGranted = remember(refresh) { Settings.canDrawOverlays(context) }
    val notificationAccess = remember(refresh) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val accessibilityGranted = remember(refresh) {
        val expected = ComponentName(context, ScreenReaderAccessibilityService::class.java)
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
    }
    val islandRunning by IslandState.running.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Settings")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(20.dp)),
        ) {
            Text(
                text = "Permissions",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
            )

            PermissionRow("Display over other apps", overlayGranted) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            RowDivider()
            PermissionRow("Notification access", notificationAccess) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            RowDivider()
            PermissionRow("Accessibility", accessibilityGranted) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            HorizontalDivider(color = Border, thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Dynamic Island", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = when {
                            !overlayGranted -> "Grant display over other apps first"
                            islandRunning -> "Live"
                            else -> "Stopped"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = islandRunning,
                    enabled = overlayGranted,
                    onCheckedChange = { start ->
                        val intent = Intent(context, DynamicIslandService::class.java)
                        if (start) {
                            ContextCompat.startForegroundService(context, intent)
                        } else {
                            context.stopService(intent)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Black,
                        checkedTrackColor = TextPrimary,
                        checkedBorderColor = TextPrimary,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = Elevated,
                        uncheckedBorderColor = Border,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(20.dp)),
        ) {
            Text(
                text = "Maintenance",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Memory consolidation", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = "Summarize the last 24 hours into one memory.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    onClick = {
                        WorkManager.getInstance(context)
                            .enqueue(OneTimeWorkRequestBuilder<NightlyConsolidator>().build())
                        Toast.makeText(context, "Consolidation queued", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Run now", color = TextPrimary, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MemorySearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Elevated)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(TextPrimary),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text("Search memory", color = TextSecondary, fontSize = 14.sp)
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            Text(
                text = "×",
                color = TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onValueChange("") }
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = "Clear search" },
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Black else TextSecondary,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) TextPrimary else Elevated)
            .border(1.dp, if (selected) TextPrimary else Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun MemoryCard(memory: MemoryEntity, onDelete: () -> Unit) {
    val title = memory.title.ifBlank {
        when (memory.source) {
            MemorySource.NOTIFICATION -> "Notification"
            MemorySource.SCREEN -> "Screen capture"
            else -> "Memory"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Elevated)
            .border(1.dp, Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Delete",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (memory.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = memory.content,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = memory.source.ifBlank { "memory" }.uppercase(Locale.getDefault()),
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(Border),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = memory.packageName.ifBlank { "unknown" },
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTimestamp(memory.timestamp),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
internal fun ScreenHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(TextPrimary))
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Elevated)
                .border(1.dp, Border, CircleShape),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(TextPrimary),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (granted) TextPrimary else Border),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(
                text = if (granted) "Granted" else "Missing",
                color = if (granted) TextPrimary else TextSecondary,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onOpen) {
            Text("Open", color = TextPrimary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = Border,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 38.dp, end = 20.dp),
    )
}

private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault())

private fun formatTimestamp(timestamp: Long): String =
    TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
