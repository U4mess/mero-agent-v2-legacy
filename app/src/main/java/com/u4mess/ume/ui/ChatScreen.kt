package com.u4mess.ume.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.brain.AgentStep
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ChatMode
import com.u4mess.ume.brain.ChatRole
import com.u4mess.ume.brain.UiAgentResult
import com.u4mess.ume.service.ApprovalRequest
import com.u4mess.ume.service.IslandAgent
import com.u4mess.ume.voice.VoiceInputManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/** Lets the floating Ask button focus the Chat composer. */
object ChatFocusRequest {
    val requests = MutableStateFlow(0L)

    fun request() {
        requests.value += 1
    }
}

private val WarmAccent = Color(0xFFD9A15B)
private val UserShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomEnd = 6.dp,
    bottomStart = 20.dp,
)
private val UmeShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomEnd = 20.dp,
    bottomStart = 6.dp,
)

@Composable
fun ChatScreen() {
    val messages by ChatController.messages.collectAsState()
    val generating by ChatController.generating.collectAsState()
    val mode by ChatController.mode.collectAsState()
    val agentStatus by IslandAgent.status.collectAsState()
    val approval by IslandAgent.approval.collectAsState()
    val listening by VoiceInputManager.isListening.collectAsState()
    val partialTranscript by VoiceInputManager.partialTranscript.collectAsState()
    val rmsLevel by VoiceInputManager.rmsLevel.collectAsState()
    val voiceError by VoiceInputManager.errorMessage.collectAsState()
    val charging = rememberCharging()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val submitVoice: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            ChatController.send(text)
            input = ""
        }
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            VoiceInputManager.startListening(submitVoice)
        } else {
            Toast.makeText(context, "Microphone permission is required.", Toast.LENGTH_SHORT).show()
        }
    }
    val onMicClick: () -> Unit = {
        when {
            VoiceInputManager.isListening.value -> VoiceInputManager.stopListening()
            !VoiceInputManager.hasPermission(context) ->
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> VoiceInputManager.startListening(submitVoice)
        }
    }

    LaunchedEffect(listening, partialTranscript) {
        if (listening && partialTranscript.isNotBlank()) {
            input = partialTranscript
        }
    }

    LaunchedEffect(voiceError) {
        voiceError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            VoiceInputManager.clearError()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (VoiceInputManager.isListening.value) VoiceInputManager.cancel()
        }
    }

    val mood = when {
        approval != null -> MonkeyMood.ACTING
        agentStatus?.active == true -> MonkeyMood.ACTING
        generating -> MonkeyMood.THINKING
        charging -> MonkeyMood.CHARGING
        else -> MonkeyMood.IDLE
    }
    val moodText = when {
        approval != null -> "Waiting for your approval"
        agentStatus?.active == true -> "Working on your phone..."
        generating -> "Thinking..."
        charging -> "On the charger"
        else -> "Idle"
    }

    LaunchedEffect(Unit) {
        ChatFocusRequest.requests.collect { request ->
            if (request > 0) {
                inputFocus.requestFocus()
                keyboard?.show()
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .then(if (imeVisible) Modifier.imePadding() else Modifier),
    ) {
        ChatHeader(
            mood = mood,
            subtitle = moodText,
            generating = generating,
            mode = mode,
            onModeChange = ChatController::setMode,
            onStop = { ChatController.stop() },
        )

        if (messages.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                body = "Ask Ume from the floating pill or type below.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }

        approval?.let { pending ->
            ApprovalBanner(
                approval = pending,
                onApprove = { IslandAgent.approve() },
                onCancel = { IslandAgent.cancelApproval() },
            )
        }

        ChatInput(
            value = input,
            onValueChange = { input = it },
            generating = generating,
            focusRequester = inputFocus,
            imeVisible = imeVisible,
            isListening = listening,
            rmsLevel = rmsLevel,
            onMicClick = onMicClick,
            onSend = {
                if (input.isNotBlank()) {
                    ChatController.send(input)
                    input = ""
                }
            },
        )
    }
}

@Composable
private fun ChatHeader(
    mood: MonkeyMood,
    subtitle: String,
    generating: Boolean,
    mode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmeMonkeyFace(Modifier.size(40.dp), mood = mood)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Ume",
                    color = TextPrimary,
                    fontFamily = UmeSerif,
                    fontSize = 23.sp,
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (generating) {
                Text(
                    text = "Stop",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Elevated)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "MODE",
                color = TextSecondary,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            ChatMode.entries.forEach { option ->
                ChatModePill(
                    label = option.label,
                    selected = mode == option,
                    onClick = { onModeChange(option) },
                )
                Spacer(Modifier.width(5.dp))
            }
        }
    }
}

@Composable
private fun ChatModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Black else TextSecondary,
        fontSize = 10.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TextPrimary else Elevated)
            .border(1.dp, if (selected) TextPrimary else Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun MessageBubble(message: ChatController.Message) {
    val dark = UmeDark
    var agentExpanded by rememberSaveable { mutableStateOf(false) }

    if (message.role == ChatRole.USER) {
        val bubbleColor = if (dark) Color(0xFF9E582B) else Color(0xFFB86B3A)

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .wrapContentWidth(Alignment.End)
                    .clip(UserShape)
                    .background(bubbleColor)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatChatTime(message.createdAt),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    } else {
        val bubbleColor = if (dark) Color(0xFF1A1918) else Color(0xFFFFFFFF)
        val bubbleBorder = if (dark) Color(0xFF2C2A28) else Color(0xFFE6E2DA)
        val badge = when {
            message.proactive -> "UME \u00B7 DAILY SUMMARY"
            message.agent != null -> "UME \u00B7 AGENT"
            else -> null
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            UmeMonkeyFace(
                Modifier.size(28.dp),
                mood = MonkeyMood.IDLE,
            )
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier.then(
                    if (message.agent != null) {
                        Modifier.clickable { agentExpanded = !agentExpanded }
                    } else {
                        Modifier
                    },
                ),
            ) {
                if (badge != null) {
                    Text(
                        text = badge,
                        color = if (message.proactive) WarmAccent else TextSecondary,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Column(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .wrapContentWidth(Alignment.Start)
                        .then(if (dark) Modifier else Modifier.shadow(1.dp, UmeShape))
                        .clip(UmeShape)
                        .background(bubbleColor)
                        .border(
                            1.dp,
                            if (message.proactive) WarmAccent else bubbleBorder,
                            UmeShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    if (message.agent != null) {
                        AgentStatusCard(message.agent, expanded = agentExpanded)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatChatTime(message.createdAt),
                            color = TextSecondary.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.End),
                        )
                    } else {
                        val content = when {
                            message.text.isBlank() && message.streaming -> "Thinking..."
                            message.streaming -> message.text + "|"
                            else -> message.text
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = content,
                                color = if (message.text.isBlank() && message.streaming) {
                                    TextSecondary
                                } else {
                                    TextPrimary
                                },
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = formatChatTime(message.createdAt),
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentStatusCard(result: UiAgentResult, expanded: Boolean) {
    var technical by rememberSaveable { mutableStateOf(false) }

    val stopped = result.message.contains("stopped", ignoreCase = true) ||
        result.details.any { it.label == "Cancelled" }
    val count = result.details.size.coerceAtLeast(result.steps.size)
    val lastDetail = result.details.lastOrNull()?.detail.orEmpty()
    val summary = when {
        lastDetail.isNotBlank() -> lastDetail
        stopped -> "Stopped"
        else -> result.message
    }
    val badge = when {
        stopped -> "Stopped"
        result.success -> "Completed in $count steps"
        else -> "Failed after $count steps"
    }
    val badgeColor = when {
        stopped -> TextSecondary
        result.success -> WarmAccent
        else -> Color(0xFFE07A5F)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = badge,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = if (expanded) "\u25B4" else "\u25BE",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (result.details.isEmpty()) {
                result.steps.forEach { line ->
                    Text(
                        text = line,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            } else {
                result.details.forEach { step ->
                    AgentStepRow(step, technical)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (technical) "Hide details" else "Details",
                color = TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { technical = !technical }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun AgentStepRow(step: AgentStep, technical: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (step.ok) WarmAccent else Color(0xFFE07A5F)),
        )
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${step.step}. ${step.label}",
                color = TextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            )
            if (technical) {
                Text(
                    text = "${step.action} \u00B7 pkg=${step.packageName ?: "-"} \u00B7 " +
                        "node=${step.nodeId ?: "-"} \u00B7 ${step.latencyMs}ms",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    color = TextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ApprovalBanner(
    approval: ApprovalRequest,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    val dark = UmeDark
    val bubbleColor = if (dark) Color(0xFF1A1918) else Color(0xFFFFFFFF)
    val bubbleBorder = if (dark) Color(0xFF2C2A28) else Color(0xFFE6E2DA)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .then(if (dark) Modifier else Modifier.shadow(1.dp, UmeShape))
            .clip(UmeShape)
            .background(bubbleColor)
            .border(1.dp, bubbleBorder, UmeShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UmeMonkeyFace(Modifier.size(28.dp), mood = MonkeyMood.ACTING)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Needs your approval",
                color = WarmAccent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            Text(
                text = approval.prompt,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ApprovalButton("Approve", filled = true, onClick = onApprove)
        Spacer(Modifier.width(4.dp))
        ApprovalButton("Cancel", filled = false, onClick = onCancel)
    }
}

@Composable
private fun ApprovalButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (filled) Black else TextPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) WarmAccent else Color.Transparent)
            .border(1.dp, if (filled) WarmAccent else Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    focusRequester: FocusRequester,
    imeVisible: Boolean,
    isListening: Boolean,
    rmsLevel: Float,
    onMicClick: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = if (imeVisible) 8.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Elevated)
                .border(1.dp, Border, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = !generating,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(TextPrimary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = if (generating) "Generating..." else "Message Ume",
                                color = TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            VoiceMicButton(
                isListening = isListening,
                rmsLevel = rmsLevel,
                onClick = onMicClick,
                tint = TextPrimary,
                border = Border,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Send",
                color = if (value.isNotBlank() && !generating) Black else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (value.isNotBlank() && !generating) TextPrimary else Border)
                    .clickable(enabled = value.isNotBlank() && !generating, onClick = onSend)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

private fun formatChatTime(timestamp: Long): String =
    CHAT_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
