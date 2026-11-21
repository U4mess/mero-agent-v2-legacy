package com.u4mess.ume.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.u4mess.ume.service.IslandSettings
import com.u4mess.ume.service.IslandState
import com.u4mess.ume.settings.CloudProvider
import com.u4mess.ume.settings.CloudSettings
import com.u4mess.ume.settings.ThemeMode
import com.u4mess.ume.settings.ThemeSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
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
    val charging = rememberCharging()
    var latestSummary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(keyword, sourceFilter, revisions) {
        memories = withContext(Dispatchers.IO) {
            repository.search(keyword, sourceFilter.ifBlank { null })
        }
    }

    LaunchedEffect(revisions) {
        latestSummary = withContext(Dispatchers.IO) {
            repository.search(source = MemorySource.SUMMARY, limit = 1).firstOrNull()?.content
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

        MemoryHero(
            charging = charging,
            latestSummary = latestSummary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(10.dp))

        Column(Modifier.padding(horizontal = 20.dp)) {
            MemorySearchField(keyword, onValueChange = { keyword = it })
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterPill("All", sourceFilter.isEmpty()) { sourceFilter = "" }
                }
                item {
                    FilterPill("Notifications", sourceFilter == MemorySource.NOTIFICATION) {
                        sourceFilter = MemorySource.NOTIFICATION
                    }
                }
                item {
                    FilterPill("Screen", sourceFilter == MemorySource.SCREEN) {
                        sourceFilter = MemorySource.SCREEN
                    }
                }
                item {
                    FilterPill("Actions", sourceFilter == MemorySource.ACTION) {
                        sourceFilter = MemorySource.ACTION
                    }
                }
                item {
                    FilterPill("Summaries", sourceFilter == MemorySource.SUMMARY) {
                        sourceFilter = MemorySource.SUMMARY
                    }
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
            val grouped = memories.groupBy { dateBucket(it.timestamp) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                grouped.forEach { (label, group) ->
                    item(key = "date_$label", span = { GridItemSpan(maxLineSpan) }) {
                        DateHeader(label)
                    }
                    items(group, key = { it.id }) { memory ->
                        BentoMemoryCard(
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
    val themeMode by ThemeSettings.mode.collectAsState()
    val cloudEnabled by CloudSettings.enabled.collectAsState()
    val cloudProvider by CloudSettings.provider.collectAsState()
    val cloudModel by CloudSettings.model.collectAsState()
    val customBaseUrl by CloudSettings.baseUrl.collectAsState()
    var apiKey by remember(cloudProvider) { mutableStateOf(CloudSettings.apiKey().orEmpty()) }
    var keySaved by remember(cloudProvider) { mutableStateOf(CloudSettings.hasKey()) }
    var yOffset by remember { mutableFloatStateOf(IslandSettings.yOffsetDp.value.toFloat()) }
    var islandWidth by remember { mutableFloatStateOf(IslandSettings.widthDp.value.toFloat()) }

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
                .shadow(3.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(24.dp)),
        ) {
            Text(
                text = "Appearance",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThemeMode.entries.forEach { option ->
                    ModelOptionPill(
                        label = option.label,
                        selected = themeMode == option,
                        modifier = Modifier.weight(1f),
                    ) {
                        ThemeSettings.setMode(option)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .shadow(3.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(24.dp)),
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

            HorizontalDivider(color = Border, thickness = 1.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Dynamic Island Y-Offset",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${yOffset.roundToInt()} dp",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Slider(
                    value = yOffset,
                    onValueChange = {
                        yOffset = it
                        IslandSettings.previewYOffset(it.roundToInt())
                    },
                    onValueChangeFinished = {
                        IslandSettings.persistYOffset(yOffset.roundToInt())
                    },
                    valueRange = 0f..IslandSettings.MAX_Y_OFFSET_DP.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = TextPrimary,
                        activeTrackColor = TextPrimary,
                        inactiveTrackColor = Border,
                        activeTickColor = TextPrimary,
                        inactiveTickColor = Border,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        text = "Dynamic Island Width",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${islandWidth.roundToInt()} dp",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Slider(
                    value = islandWidth,
                    onValueChange = {
                        islandWidth = it
                        IslandSettings.previewWidth(it.roundToInt())
                    },
                    onValueChangeFinished = {
                        IslandSettings.persistWidth(islandWidth.roundToInt())
                    },
                    valueRange = IslandSettings.MIN_WIDTH_DP.toFloat()..IslandSettings.MAX_WIDTH_DP.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = TextPrimary,
                        activeTrackColor = TextPrimary,
                        inactiveTrackColor = Border,
                        activeTickColor = TextPrimary,
                        inactiveTickColor = Border,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .shadow(3.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(24.dp)),
        ) {
            Text(
                text = "Cloud Action Brain",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CloudProvider.entries.forEach { option ->
                    item(key = option.id) {
                        ModelOptionPill(
                            label = option.label,
                            selected = cloudProvider == option,
                            modifier = Modifier.width(94.dp),
                        ) {
                            CloudSettings.selectProvider(option)
                        }
                    }
                }
            }

            if (cloudProvider == CloudProvider.CUSTOM) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                ) {
                    Text("Base URL", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Black)
                            .border(1.dp, Border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = customBaseUrl,
                            onValueChange = CloudSettings::setBaseUrl,
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(TextPrimary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (customBaseUrl.isEmpty()) {
                                        Text(
                                            "https://host/v1",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = cloudProvider.endpoint,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Black)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(TextPrimary),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box {
                                if (apiKey.isEmpty()) {
                                    Text("API key", color = TextSecondary, fontSize = 13.sp)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save",
                    color = Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TextPrimary)
                        .clickable {
                            CloudSettings.saveApiKey(apiKey)
                            keySaved = CloudSettings.hasKey()
                            Toast.makeText(
                                context,
                                if (keySaved) "API key saved" else "API key cleared",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
                if (keySaved) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Clear",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                CloudSettings.saveApiKey("")
                                apiKey = ""
                                keySaved = false
                                Toast.makeText(context, "API key cleared", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                    )
                }
            }

            Text(
                text = if (keySaved) {
                    "Encrypted with the Android Keystore"
                } else {
                    "No API key saved"
                },
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp),
            ) {
                Text("Model ID", color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Black)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = cloudModel,
                        onValueChange = CloudSettings::setModel,
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(TextPrimary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box {
                                if (cloudModel.isEmpty()) {
                                    Text("model-id", color = TextSecondary, fontSize = 13.sp)
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hybrid Cloud Actions", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = if (cloudEnabled) {
                            "Phone actions through ${cloudProvider.label}"
                        } else {
                            "Off - action prompts will ask you to enable it"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = cloudEnabled,
                    onCheckedChange = { CloudSettings.setEnabled(it) },
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
                .shadow(3.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(24.dp)),
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
                text = "Ã—",
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
private fun ModelOptionPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) TextPrimary else Elevated)
            .border(1.dp, if (selected) TextPrimary else Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Black else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
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

private val AmberAccent = Color(0xFFE0A458)

@Composable
private fun MemoryHero(charging: Boolean, latestSummary: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val greeting = remember { greetingForHour(LocalTime.now().hour) }
    val status = if (charging) "On the charger" else "Ready on-device"
    val summary = latestSummary?.replace('\n', ' ')?.take(150)
        ?: "Everything you notice, remembered on this device."

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(6.dp, shape)
                .clip(shape)
                .background(Elevated)
                .border(1.dp, Border, shape)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (charging) AmberAccent else TextPrimary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = greeting,
                color = TextPrimary,
                fontFamily = UmeSerif,
                fontSize = 27.sp,
                lineHeight = 31.sp,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary,
                color = TextSecondary,
                fontFamily = UmeSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
        }

        UmeMonkeyFace(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 14.dp, y = (-12).dp)
                .size(66.dp),
            mood = if (charging) MonkeyMood.CHARGING else MonkeyMood.IDLE,
        )
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        color = TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun BentoMemoryCard(memory: MemoryEntity, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val title = memory.title.ifBlank { memoryFallbackTitle(memory.source) }
    val packageLabel = memory.packageName.ifBlank {
        if (memory.source == MemorySource.ACTION || memory.source == MemorySource.SUMMARY) {
            "Ume Agent"
        } else {
            "unknown"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(Elevated)
            .border(1.dp, Border, shape)
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BentoSourceIcon(memory.source)
            Spacer(Modifier.width(6.dp))
            Text(
                text = memorySourceLabel(memory.source),
                color = TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.7.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "\u00D7",
                color = TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 5.dp)
                    .semantics { contentDescription = "Delete memory" },
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (memory.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = memory.content.replace('\n', ' '),
                color = TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "$packageLabel \u00B7 ${formatTimestamp(memory.timestamp)}",
            color = TextSecondary,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BentoSourceIcon(source: String) {
    val tint = when (source) {
        MemorySource.ACTION -> AmberAccent
        MemorySource.SUMMARY -> Color(0xFFB07A52)
        else -> TextSecondary
    }
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.1.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        when (source) {
            MemorySource.NOTIFICATION -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.1f, h * 0.16f),
                    size = Size(w * 0.8f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.16f),
                    style = stroke,
                )
                val tail = Path().apply {
                    moveTo(w * 0.34f, h * 0.68f)
                    lineTo(w * 0.34f, h * 0.88f)
                    lineTo(w * 0.52f, h * 0.68f)
                }
                drawPath(tail, tint, style = stroke)
            }

            MemorySource.SCREEN -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.18f),
                    size = Size(w * 0.76f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.72f, h * 0.42f), 1.1.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.62f), Offset(w * 0.58f, h * 0.62f), 1.1.dp.toPx(), StrokeCap.Round)
            }

            MemorySource.ACTION -> {
                val check = Path().apply {
                    moveTo(w * 0.18f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.76f)
                    lineTo(w * 0.84f, h * 0.26f)
                }
                drawPath(check, tint, style = stroke)
            }

            MemorySource.SUMMARY -> {
                for (i in 0..2) {
                    val y = h * (0.3f + i * 0.2f)
                    drawLine(
                        color = tint,
                        start = Offset(w * 0.16f, y),
                        end = Offset(w * (if (i == 2) 0.64f else 0.84f), y),
                        strokeWidth = 1.1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            else -> drawCircle(tint, w * 0.18f, Offset(w * 0.5f, h * 0.5f), style = stroke)
        }
    }
}

@Composable
internal fun rememberCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(isCharging(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return charging
}

private fun isCharging(context: Context): Boolean =
    context.getSystemService(BatteryManager::class.java)?.isCharging == true

private fun memorySourceLabel(source: String): String = when (source) {
    MemorySource.NOTIFICATION -> "NOTIFICATION"
    MemorySource.SCREEN -> "SCREEN"
    MemorySource.ACTION -> "ACTION"
    MemorySource.SUMMARY -> "SUMMARY"
    else -> source.uppercase(Locale.getDefault())
}

private fun memoryFallbackTitle(source: String): String = when (source) {
    MemorySource.NOTIFICATION -> "Notification"
    MemorySource.SCREEN -> "Screen capture"
    MemorySource.ACTION -> "Agent action"
    MemorySource.SUMMARY -> "Daily summary"
    else -> "Memory"
}

private fun dateBucket(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    }
}

private fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
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

private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("d MMM \u00B7 HH:mm", Locale.getDefault())

private fun formatTimestamp(timestamp: Long): String =
    TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

