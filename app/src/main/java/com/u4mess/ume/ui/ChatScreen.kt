package com.u4mess.ume.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.brain.AgentStep
import com.u4mess.ume.brain.ChatAttachment
import com.u4mess.ume.brain.ChatAttachmentExtractor
import com.u4mess.ume.brain.ChatAttachmentKind
import com.u4mess.ume.brain.ChatAttachmentStore
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ChatMode
import com.u4mess.ume.brain.ChatRole
import com.u4mess.ume.brain.UiAgentResult
import com.u4mess.ume.net.WebResult
import com.u4mess.ume.service.ApprovalRequest
import com.u4mess.ume.service.IslandAgent
import com.u4mess.ume.voice.VoiceChatSession
import com.u4mess.ume.voice.VoiceInputManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    val webEnabled by ChatController.webEnabled.collectAsState()
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
    var showVoiceChat by rememberSaveable { mutableStateOf(false) }
    var selectedWebSources by remember { mutableStateOf<List<WebResult>?>(null) }
    var stagedAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var previousMessageCount by remember { mutableIntStateOf(0) }
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val submitVoice: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            ChatController.send(text)
            input = ""
        }
    }
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val attachment = runCatching {
                    ChatAttachmentExtractor.extract(context, uri)
                }.getOrNull()
                if (attachment == null || attachment.text.isBlank()) {
                    Toast.makeText(context, "Could not read this file", Toast.LENGTH_SHORT).show()
                } else {
                    stagedAttachment = attachment
                }
            }
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

    LaunchedEffect(Unit) {
        ChatAttachmentStore.sharedText.collect { text ->
            if (!text.isNullOrBlank()) {
                input = text
                ChatAttachmentStore.consumeSharedText()
            }
        }
    }

    LaunchedEffect(Unit) {
        ChatAttachmentStore.sharedAttachment.collect { attachment ->
            if (attachment != null) {
                stagedAttachment = attachment
                ChatAttachmentStore.consumeSharedAttachment()
            }
        }
    }

    LaunchedEffect(Unit) {
        VoiceChatSession.openRequests.collect { request ->
            if (request > 0) {
                showVoiceChat = true
                VoiceChatSession.consumeOpenRequest()
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isEmpty()) {
            previousMessageCount = 0
            return@LaunchedEffect
        }
        val lastIndex = messages.lastIndex
        if (messages.size != previousMessageCount) {
            previousMessageCount = messages.size
            listState.animateScrollToItem(lastIndex)
        } else {
            listState.scrollToItem(lastIndex)
        }
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
            webEnabled = webEnabled,
            onModeChange = ChatController::setMode,
            onToggleWeb = { ChatController.setWebEnabled(!webEnabled) },
            onTalk = { showVoiceChat = true },
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
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onOpenWebSources = { sources -> selectedWebSources = sources },
                    )
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

        stagedAttachment?.let { attachment ->
            AttachmentChip(attachment) { stagedAttachment = null }
        }

        ChatInput(
            value = input,
            onValueChange = { input = it },
            generating = generating,
            hasAttachment = stagedAttachment != null,
            focusRequester = inputFocus,
            imeVisible = imeVisible,
            isListening = listening,
            rmsLevel = rmsLevel,
            onMicClick = onMicClick,
            onAttachClick = {
                attachLauncher.launch(arrayOf("application/pdf", "image/*", "text/*"))
            },
            onSend = {
                if (input.isNotBlank() || stagedAttachment != null) {
                    ChatController.send(input, stagedAttachment)
                    input = ""
                    stagedAttachment = null
                }
            },
        )
    }

    if (showVoiceChat) {
        VoiceChatSheet(onDismiss = { showVoiceChat = false })
    }

    selectedWebSources?.let { sources ->
        WebSourcesSheet(sources) { selectedWebSources = null }
    }
}

@Composable
private fun ChatHeader(
    mood: MonkeyMood,
    subtitle: String,
    generating: Boolean,
    mode: ChatMode,
    webEnabled: Boolean,
    onModeChange: (ChatMode) -> Unit,
    onToggleWeb: () -> Unit,
    onTalk: () -> Unit,
    onStop: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val hapticTap = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val statusColor = when (mood) {
        MonkeyMood.THINKING, MonkeyMood.ACTING -> Accent
        MonkeyMood.CHARGING -> Color(0xFFE0A458)
        else -> TextSecondary
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UmeMonkeyFace(Modifier.size(32.dp), mood = mood)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ume",
                        color = TextPrimary,
                        fontFamily = UmeSerif,
                        fontSize = 21.sp,
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            TalkModeButton(onClick = { hapticTap(); onTalk() })
            if (generating) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Stop",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Elevated)
                        .border(0.75.dp, Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Elevated)
                    .border(0.75.dp, Border, RoundedCornerShape(14.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatMode.entries.forEach { option ->
                    val selected = mode == option
                    val optionBackground by animateColorAsState(
                        targetValue = if (selected) TextPrimary else Color.Transparent,
                        animationSpec = tween(180),
                        label = "modeBackground",
                    )
                    val optionText by animateColorAsState(
                        targetValue = if (selected) Black else TextSecondary,
                        animationSpec = tween(180),
                        label = "modeText",
                    )
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(optionBackground)
                            .clickable {
                                hapticTap()
                                onModeChange(option)
                            }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            color = optionText,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val webBackground by animateColorAsState(
                targetValue = if (webEnabled) Accent else Elevated,
                animationSpec = tween(180),
                label = "webBackground",
            )
            val webText by animateColorAsState(
                targetValue = if (webEnabled) OnAccent else TextSecondary,
                animationSpec = tween(180),
                label = "webText",
            )
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(webBackground)
                    .border(0.75.dp, if (webEnabled) Accent else Border, RoundedCornerShape(14.dp))
                    .clickable {
                        hapticTap()
                        onToggleWeb()
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Web",
                    color = webText,
                    fontSize = 10.sp,
                    fontWeight = if (webEnabled) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun TalkModeButton(onClick: () -> Unit) {
    val tint = TextPrimary
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Elevated)
            .border(0.75.dp, Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.4.dp.toPx()
            val heights = listOf(0.35f, 0.72f, 0.5f)
            heights.forEachIndexed { index, fraction ->
                val x = w * (0.22f + index * 0.28f)
                val half = h * fraction / 2f
                drawLine(
                    color = tint,
                    start = Offset(x, h / 2f - half),
                    end = Offset(x, h / 2f + half),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text("Talk", color = TextPrimary, fontSize = 11.sp)
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
private fun MessageBubble(
    message: ChatController.Message,
    onOpenWebSources: (List<WebResult>) -> Unit,
) {
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
                message.attachment?.let { attachment ->
                    AttachmentInlinePill(attachment)
                    Spacer(Modifier.height(4.dp))
                }
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
                        if (message.webSources.isNotEmpty()) {
                            WebSourcesBadge(message.webSources, onOpenWebSources)
                        }
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
                        if (message.webSources.isNotEmpty()) {
                            WebSourcesBadge(message.webSources, onOpenWebSources)
                            Spacer(Modifier.height(4.dp))
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
private fun WebSourcesBadge(
    sources: List<WebResult>,
    onOpen: (List<WebResult>) -> Unit,
) {
    Text(
        text = "WEB \u00B7 ${sources.size} sources",
        color = WarmAccent,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpen(sources) }
            .padding(vertical = 2.dp, horizontal = 2.dp),
    )
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
private fun AttachmentChip(
    attachment: ChatAttachment,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Elevated)
            .border(0.75.dp, Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttachmentTypeBadge(attachment.kind, Color.Transparent, TextSecondary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                color = TextPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = attachment.kind.label.lowercase() + " attachment",
                color = TextSecondary,
                fontSize = 9.sp,
            )
        }
        Text(
            text = "\u00D7",
            color = TextSecondary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClear)
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = "Clear attachment" },
        )
    }
}

@Composable
private fun AttachmentTypeBadge(
    kind: ChatAttachmentKind,
    background: Color,
    foreground: Color,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(0.75.dp, Border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (kind) {
                ChatAttachmentKind.PDF -> "PDF"
                ChatAttachmentKind.IMAGE -> "IMG"
                ChatAttachmentKind.TEXT -> "TXT"
            },
            color = foreground,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun AttachmentInlinePill(attachment: ChatAttachment) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (attachment.kind) {
                ChatAttachmentKind.PDF -> "PDF"
                ChatAttachmentKind.IMAGE -> "IMG"
                ChatAttachmentKind.TEXT -> "TXT"
            },
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = attachment.name,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    hasAttachment: Boolean,
    focusRequester: FocusRequester,
    imeVisible: Boolean,
    isListening: Boolean,
    rmsLevel: Float,
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = (value.isNotBlank() || hasAttachment) && !generating
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
                .clip(RoundedCornerShape(22.dp))
                .background(Elevated)
                .border(0.75.dp, Border, RoundedCornerShape(22.dp))
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Black)
                    .border(0.75.dp, Border, CircleShape)
                    .clickable(enabled = !generating, onClick = onAttachClick)
                    .semantics { contentDescription = "Attach file" },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = TextPrimary, fontSize = 18.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(6.dp))
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
                color = if (canSend) OnAccent else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (canSend) Accent else Border)
                    .clickable(enabled = canSend, onClick = onSend)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

private fun formatChatTime(timestamp: Long): String =
    CHAT_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
