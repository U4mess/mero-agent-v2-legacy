package com.u4mess.ume.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.u4mess.ume.service.DynamicIslandService
import com.u4mess.ume.service.IslandState
import com.u4mess.ume.service.PromptInbox

@Composable
fun ChatScreen() {
    val prompts by PromptInbox.prompts.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Chat")
        if (prompts.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                body = "Tap the Ume pill anywhere on screen to send your first prompt.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(prompts) { prompt ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = prompt,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Elevated)
                                .border(1.dp, Border, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Memory")
        EmptyState(
            title = "Nothing remembered yet",
            body = "Ume keeps your conversations, screens and documents in a private on-device memory.",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun ModelsScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Models")
        EmptyState(
            title = "No models installed",
            body = "Local models will show up here. Ume runs them entirely on this device.",
            modifier = Modifier.weight(1f),
        )
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
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
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

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ScreenHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(TextPrimary))
        Spacer(Modifier.width(10.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
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
