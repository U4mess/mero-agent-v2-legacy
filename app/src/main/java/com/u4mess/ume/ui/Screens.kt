package com.u4mess.ume.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
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
import com.u4mess.ume.sensors.MediaDocumentIndexer
import com.u4mess.ume.sensors.ScreenReaderAccessibilityService
import com.u4mess.ume.service.DynamicIslandService
import com.u4mess.ume.service.IslandSettings
import com.u4mess.ume.service.IslandState
import com.u4mess.ume.settings.CloudProvider
import com.u4mess.ume.settings.CloudSettings
import com.u4mess.ume.settings.ThemeMode
import com.u4mess.ume.settings.ThemeSettings
import com.u4mess.ume.settings.WebSearchSettings
import com.u4mess.ume.ui.components.UmeCard
import com.u4mess.ume.ui.components.UmeSectionHeader
import com.u4mess.ume.ui.components.UmeSlider
import com.u4mess.ume.voice.VoiceOutputManager
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
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch(Dispatchers.IO) {
                val indexed = MediaDocumentIndexer.indexUri(uri, null)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (indexed) "Document indexed" else "Nothing to index",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    var keyword by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var selectedMemory by remember { mutableStateOf<MemoryEntity?>(null) }
    val charging = rememberCharging()

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
        ScreenHeader("Memory")

        MemoryHero(
            charging = charging,
            memoryCount = memories.size,
            onCapture = captureScreen,
            onImport = { importLauncher.launch(arrayOf("application/pdf", "image/*")) },
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
                item {
                    FilterPill("Screenshots", sourceFilter == MemorySource.SCREENSHOT) {
                        sourceFilter = MemorySource.SCREENSHOT
                    }
                }
                item {
                    FilterPill("PDFs", sourceFilter == MemorySource.PDF) {
                        sourceFilter = MemorySource.PDF
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
                            onOpen = { selectedMemory = memory },
                            onDelete = {
                                scope.launch(Dispatchers.IO) { repository.delete(memory.id) }
                            },
                        )
                    }
                }
            }
        }
    }

    selectedMemory?.let { memory ->
        MemoryDetailSheet(memory) { selectedMemory = null }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh++
                MediaDocumentIndexer.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refresh++
        if (granted) MediaDocumentIndexer.refresh(context)
    }
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val mediaGranted = remember(refresh) {
        MediaDocumentIndexer.hasMediaPermission(context)
    }
    val mediaIndexEnabled by MediaDocumentIndexer.enabled.collectAsState()
    val voiceReplies by VoiceOutputManager.enabled.collectAsState()
    val speechRate by VoiceOutputManager.rate.collectAsState()
    var braveKey by remember { mutableStateOf(WebSearchSettings.apiKey().orEmpty()) }
    var braveSaved by remember { mutableStateOf(WebSearchSettings.hasKey()) }

    val overlayGranted = remember(refresh) { Settings.canDrawOverlays(context) }
    val microphoneGranted = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
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
    val batteryUnrestricted = remember(refresh) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
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
    var xOffset by remember { mutableFloatStateOf(IslandSettings.xOffsetDp.value.toFloat()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader("Settings")

        UmeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            UmeSectionHeader(
                title = "Appearance",
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.take(3).forEach { option ->
                        ModelOptionPill(
                            label = option.label,
                            selected = themeMode == option,
                            modifier = Modifier.weight(1f),
                        ) {
                            ThemeSettings.setMode(option)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.drop(3).forEach { option ->
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
        }

        Spacer(Modifier.height(12.dp))

        UmeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            UmeSectionHeader(
                title = "Permissions",
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
            RowDivider()
            PermissionRow(
                title = "Microphone",
                granted = microphoneGranted,
                actionLabel = if (microphoneGranted) "Open" else "Grant",
            ) {
                if (microphoneGranted) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            RowDivider()
            PermissionRow(
                title = "Photos & Screenshots",
                granted = mediaGranted,
                actionLabel = if (mediaGranted) "Open" else "Grant",
            ) {
                if (mediaGranted) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                } else {
                    mediaPermissionLauncher.launch(mediaPermission)
                }
            }
            RowDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-index Screenshots & PDFs", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = when {
                            !mediaGranted -> "Grant Photos access to scan"
                            mediaIndexEnabled -> "On"
                            else -> "Off"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = mediaIndexEnabled,
                    onCheckedChange = { MediaDocumentIndexer.setEnabled(context, it) },
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
            RowDivider()
            PermissionRow("Battery unrestricted", batteryUnrestricted) {
                val request = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(request) }.onFailure {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
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
                UmeSlider(
                    value = yOffset,
                    onValueChange = {
                        yOffset = it
                        IslandSettings.previewYOffset(it.roundToInt())
                    },
                    onValueChangeFinished = {
                        IslandSettings.persistYOffset(yOffset.roundToInt())
                    },
                    valueRange = 0f..IslandSettings.MAX_Y_OFFSET_DP.toFloat(),
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
                UmeSlider(
                    value = islandWidth,
                    onValueChange = {
                        islandWidth = it
                        IslandSettings.previewWidth(it.roundToInt())
                    },
                    onValueChangeFinished = {
                        IslandSettings.persistWidth(islandWidth.roundToInt())
                    },
                    valueRange = IslandSettings.MIN_WIDTH_DP.toFloat()..IslandSettings.MAX_WIDTH_DP.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        text = "Dynamic Island X-Offset",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${if (xOffset > 0) "+" else ""}${xOffset.roundToInt()} dp",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                UmeSlider(
                    value = xOffset,
                    onValueChange = {
                        xOffset = it
                        IslandSettings.previewXOffset(it.roundToInt())
                    },
                    onValueChangeFinished = {
                        IslandSettings.persistXOffset(xOffset.roundToInt())
                    },
                    valueRange = IslandSettings.MIN_X_OFFSET_DP.toFloat()..IslandSettings.MAX_X_OFFSET_DP.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        UmeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            UmeSectionHeader(
                title = "Voice",
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Voice replies (TTS)", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = if (voiceReplies) "Ume reads replies aloud" else "Off",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = voiceReplies,
                    onCheckedChange = { VoiceOutputManager.setEnabled(it) },
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Speech rate",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fx", speechRate),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                UmeSlider(
                    value = speechRate,
                    onValueChange = { VoiceOutputManager.setRate(it) },
                    valueRange = 0.8f..1.5f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        UmeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            UmeSectionHeader(
                title = "Web Search",
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp),
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
                        value = braveKey,
                        onValueChange = { braveKey = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(TextPrimary),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box {
                                if (braveKey.isEmpty()) {
                                    Text(
                                        "Brave Search API key (optional)",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save",
                    color = if (braveKey.isNotBlank()) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = braveKey.isNotBlank()) {
                            WebSearchSettings.saveApiKey(braveKey)
                            braveSaved = WebSearchSettings.hasKey()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                if (braveSaved) {
                    Text(
                        text = "Clear",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                WebSearchSettings.saveApiKey("")
                                braveKey = ""
                                braveSaved = false
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
            Text(
                text = "Uses Brave Search API when set; falls back to DuckDuckGo automatically.",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        UmeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            UmeSectionHeader(
                title = "Cloud Action Brain",
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

        UmeCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            UmeSectionHeader(
                title = "Maintenance",
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
                text = "Ãƒâ€”",
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
private fun MemoryHero(
    charging: Boolean,
    memoryCount: Int,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val greeting = remember { greetingForHour(LocalTime.now().hour) }
    val countLabel = when (memoryCount) {
        0 -> "No memories"
        1 -> "1 memory"
        else -> "$memoryCount memories"
    }

    UmeCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        cornerRadius = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmeMonkeyFace(
                Modifier.size(28.dp),
                mood = if (charging) MonkeyMood.CHARGING else MonkeyMood.IDLE,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = countLabel,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (charging) "Charging" else greeting,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HeroActionPill("Capture", capture = true, onClick = onCapture)
            Spacer(Modifier.width(4.dp))
            HeroActionPill("Import", capture = false, onClick = onImport)
        }
    }
}

@Composable
private fun HeroActionPill(
    label: String,
    capture: Boolean,
    onClick: () -> Unit,
) {
    val tint = TextSecondary
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Black)
            .border(0.75.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val stroke = 1.2.dp.toPx()
            if (capture) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(stroke / 2f, stroke * 1.5f),
                    size = Size(size.width - stroke, size.height - stroke * 2f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                )
                drawCircle(
                    color = tint,
                    radius = size.minDimension * 0.16f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            } else {
                drawLine(
                    color = tint,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height * 0.62f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.34f, size.height * 0.46f),
                    end = Offset(size.width / 2f, size.height * 0.64f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.66f, size.height * 0.46f),
                    end = Offset(size.width / 2f, size.height * 0.64f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.18f, size.height * 0.9f),
                    end = Offset(size.width * 0.82f, size.height * 0.9f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = TextPrimary, fontSize = 10.sp, maxLines = 1)
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
private fun BentoMemoryCard(
    memory: MemoryEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = memory.title.ifBlank { memoryFallbackTitle(memory.source) }

    UmeCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .clickable(onClick = onOpen),
        contentPadding = PaddingValues(12.dp),
        cornerRadius = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BentoSourceIcon(memory.source)
            Spacer(Modifier.width(5.dp))
            Text(
                text = memorySourceLabel(memory.source),
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Black)
                    .border(0.75.dp, Border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "\u00D7",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .semantics { contentDescription = "Delete memory" },
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = title,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (memory.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = memory.content.replace('\n', ' '),
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = formatTimestamp(memory.timestamp),
            color = TextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryDetailSheet(memory: MemoryEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = memory.title.ifBlank { memoryFallbackTitle(memory.source) }
    val packageLabel = memory.packageName.ifBlank {
        if (memory.source == MemorySource.ACTION || memory.source == MemorySource.SUMMARY) {
            "Ume Agent"
        } else {
            "unknown"
        }
    }
    val fullText = buildString {
        appendLine(title)
        appendLine("Source: ${memorySourceLabel(memory.source)}")
        appendLine("Package: $packageLabel")
        appendLine("Time: ${formatTimestamp(memory.timestamp)}")
        appendLine()
        append(memory.content.ifBlank { "No content" })
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Elevated,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary.copy(alpha = 0.5f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Memory",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontFamily = UmeSerif,
                fontSize = 22.sp,
                lineHeight = 27.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${memorySourceLabel(memory.source)} \u00B7 $packageLabel",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            Text(
                text = formatTimestamp(memory.timestamp),
                color = TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Border, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = memory.content.ifBlank { "No content" },
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Copy",
                    color = Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TextPrimary)
                        .clickable {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Ume memory", fullText))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onDismiss) {
                    Text("Close", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BentoSourceIcon(source: String) {
    val tint = when (source) {
        MemorySource.ACTION -> AmberAccent
        MemorySource.SUMMARY, MemorySource.PDF -> Color(0xFFB07A52)
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

            MemorySource.SCREEN, MemorySource.SCREENSHOT -> {
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

            MemorySource.PDF -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.2f, h * 0.1f),
                    size = Size(w * 0.6f, h * 0.8f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.32f, h * 0.36f), Offset(w * 0.68f, h * 0.36f), 1.1.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.54f), Offset(w * 0.68f, h * 0.54f), 1.1.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.72f), Offset(w * 0.56f, h * 0.72f), 1.1.dp.toPx(), StrokeCap.Round)
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
    MemorySource.SCREENSHOT -> "SCREENSHOT"
    MemorySource.PDF -> "PDF"
    MemorySource.MANUAL -> "NOTE"
    else -> source.uppercase(Locale.getDefault())
}

private fun memoryFallbackTitle(source: String): String = when (source) {
    MemorySource.NOTIFICATION -> "Notification"
    MemorySource.SCREEN -> "Screen capture"
    MemorySource.ACTION -> "Agent action"
    MemorySource.SUMMARY -> "Daily summary"
    MemorySource.SCREENSHOT -> "Screenshot"
    MemorySource.PDF -> "PDF Document"
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
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String = "Open",
    onOpen: () -> Unit,
) {
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
            Text(actionLabel, color = TextPrimary, fontSize = 13.sp)
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

