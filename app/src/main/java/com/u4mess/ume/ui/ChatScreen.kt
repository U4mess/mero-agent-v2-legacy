package com.u4mess.ume.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.u4mess.ume.brain.AgentStep
import com.u4mess.ume.brain.ChatAttachment
import com.u4mess.ume.brain.ChatBackend
import com.u4mess.ume.brain.ChatAttachmentExtractor
import com.u4mess.ume.brain.ChatAttachmentKind
import com.u4mess.ume.brain.ChatAttachmentStore
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ChatMode
import com.u4mess.ume.brain.ChatRole
import com.u4mess.ume.brain.ChatSession
import com.u4mess.ume.brain.ModelManager
import com.u4mess.ume.brain.UiAgentResult
import com.u4mess.ume.brain.VisionImage
import com.u4mess.ume.net.WebResult
import com.u4mess.ume.service.ApprovalRequest
import com.u4mess.ume.service.IslandAgent
import com.u4mess.ume.ui.components.MarkdownText
import com.u4mess.ume.voice.VoiceChatSession
import com.u4mess.ume.voice.VoiceInputManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val messages by ChatController.messages.collectAsState()
    val generating by ChatController.generating.collectAsState()
    val mode by ChatController.mode.collectAsState()
    val webEnabled by ChatController.webEnabled.collectAsState()
    val sessions by ChatController.sessions.collectAsState()
    val currentSessionId by ChatController.currentSessionId.collectAsState()
    val approval by IslandAgent.approval.collectAsState()
    val listening by VoiceInputManager.isListening.collectAsState()
    val partialTranscript by VoiceInputManager.partialTranscript.collectAsState()
    val rmsLevel by VoiceInputManager.rmsLevel.collectAsState()
    val voiceError by VoiceInputManager.errorMessage.collectAsState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var showVoiceChat by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var selectedWebSources by remember { mutableStateOf<List<WebResult>?>(null) }
    var stagedAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var preparingImage by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
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

    val preparePickedImage: (Uri) -> Unit = { uri ->
        preparingImage = true
        scope.launch {
            val attachment = runCatching { VisionImage.prepare(context, uri) }.getOrNull()
            preparingImage = false
            if (attachment == null) {
                Toast.makeText(context, "Could not prepare this image", Toast.LENGTH_SHORT).show()
            } else {
                stagedAttachment = attachment
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) preparePickedImage(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) preparePickedImage(uri)
    }

    val openCamera: () -> Unit = {
        val dir = File(context.cacheDir, "vision").apply { mkdirs() }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        pendingCameraUri = uri
        runCatching { cameraLauncher.launch(uri) }.onFailure {
            pendingCameraUri = null
            Toast.makeText(context, "Camera is not available", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            Toast.makeText(context, "Camera permission is required.", Toast.LENGTH_SHORT).show()
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
            generating = generating,
            mode = mode,
            webEnabled = webEnabled,
            onModeChange = ChatController::setMode,
            onToggleWeb = { ChatController.setWebEnabled(!webEnabled) },
            onHistory = { showHistory = true },
            onNewChat = {
                ChatController.newChat()
                ChatFocusRequest.request()
            },
            onTalk = { showVoiceChat = true },
            onStop = { ChatController.stop() },
        )

        if (messages.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                body = "Ask Mero Agent from the floating pill or type below.",
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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

        if (preparingImage) {
            Text(
                text = "Preparing image...",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp),
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
            onAttachClick = { showAttachSheet = true },
            onSend = {
                when {
                    stagedAttachment?.imagePath != null &&
                        ModelManager.chatBackend.value != ChatBackend.LITERT_LM -> {
                        Toast.makeText(
                            context,
                            "Load Gemma 4 E2B (.litertlm) in Models to use vision.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    input.isNotBlank() || stagedAttachment != null -> {
                        ChatController.send(input, stagedAttachment)
                        input = ""
                        stagedAttachment = null
                    }
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

    if (showAttachSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = sheetState,
            containerColor = Black,
            contentColor = TextPrimary,
            scrimColor = Color.Black.copy(alpha = 0.72f),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Border) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            ) {
                Text(
                    text = "Add to chat",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                VisionSheetOption(
                    title = "Camera",
                    subtitle = "Take a photo with the camera",
                    onClick = {
                        showAttachSheet = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            openCamera()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                VisionSheetOption(
                    title = "Gallery",
                    subtitle = "Choose a photo from your library",
                    onClick = {
                        showAttachSheet = false
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                VisionSheetOption(
                    title = "File",
                    subtitle = "PDF, image, or text attachment",
                    onClick = {
                        showAttachSheet = false
                        attachLauncher.launch(arrayOf("application/pdf", "image/*", "text/*"))
                    },
                )
            }
        }
    }

    if (showHistory) {
        ChatHistorySheet(
            sessions = sessions,
            currentSessionId = currentSessionId,
            messageCount = ChatController::messageCount,
            onSelect = { session ->
                ChatController.switchSession(session.id)
                showHistory = false
            },
            onDelete = ChatController::deleteSession,
            onDismiss = { showHistory = false },
        )
    }
}

@Composable
private fun ChatHeader(
    generating: Boolean,
    mode: ChatMode,
    webEnabled: Boolean,
    onModeChange: (ChatMode) -> Unit,
    onToggleWeb: () -> Unit,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onTalk: () -> Unit,
    onStop: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var modeMenu by remember { mutableStateOf(false) }
    val hapticTap = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Elevated)
                        .border(0.75.dp, Border, RoundedCornerShape(16.dp))
                        .clickable {
                            hapticTap()
                            modeMenu = true
                        }
                        .padding(start = 7.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UmeMonkeyFace(Modifier.size(20.dp), mood = MonkeyMood.IDLE)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${mode.label} \u25BE",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                DropdownMenu(
                    expanded = modeMenu,
                    onDismissRequest = { modeMenu = false },
                ) {
                    ChatMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    color = if (option == mode) Accent else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (option == mode) FontWeight.Medium else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                hapticTap()
                                onModeChange(option)
                                modeMenu = false
                            },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(webBackground)
                    .border(0.75.dp, if (webEnabled) Accent else Border, RoundedCornerShape(14.dp))
                    .clickable {
                        hapticTap()
                        onToggleWeb()
                    }
                    .padding(horizontal = 9.dp),
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

        Spacer(Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(HeaderIcon.History) {
                hapticTap()
                onHistory()
            }
            if (generating) {
                HeaderIconButton(HeaderIcon.Stop) {
                    hapticTap()
                    onStop()
                }
            } else {
                HeaderIconButton(HeaderIcon.NewChat) {
                    hapticTap()
                    onNewChat()
                }
            }
            TalkModeButton(onClick = { hapticTap(); onTalk() })
        }
    }
}

private enum class HeaderIcon { History, NewChat, Stop }

@Composable
private fun HeaderIconButton(icon: HeaderIcon, onClick: () -> Unit) {
    val tint = TextSecondary
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = when (icon) {
                    HeaderIcon.History -> "Chat history"
                    HeaderIcon.NewChat -> "New chat"
                    HeaderIcon.Stop -> "Stop generating"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val stroke = 1.3.dp.toPx()
            if (icon == HeaderIcon.History) {
                drawCircle(
                    color = tint,
                    radius = size.minDimension * 0.36f,
                    style = Stroke(stroke),
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width / 2f, size.height * 0.2f),
                    end = Offset(size.width / 2f, size.height / 2f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width / 2f, size.height / 2f),
                    end = Offset(size.width * 0.72f, size.height * 0.62f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else if (icon == HeaderIcon.NewChat) {
                drawLine(
                    color = tint,
                    start = Offset(size.width / 2f, size.height * 0.14f),
                    end = Offset(size.width / 2f, size.height * 0.86f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.14f, size.height / 2f),
                    end = Offset(size.width * 0.86f, size.height / 2f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.26f, size.height * 0.26f),
                    size = Size(size.width * 0.48f, size.height * 0.48f),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(stroke),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHistorySheet(
    sessions: List<ChatSession>,
    currentSessionId: String,
    messageCount: (String) -> Int,
    onSelect: (ChatSession) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Elevated,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Border) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Chat history", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (sessions.isEmpty()) {
                    Text("No chats yet", color = TextSecondary, fontSize = 13.sp)
                }
                sessions.forEachIndexed { index, session ->
                    val count = messageCount(session.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (session.id == currentSessionId) {
                                    Accent.copy(alpha = 0.10f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .border(
                                0.75.dp,
                                if (session.id == currentSessionId) Accent.copy(alpha = 0.45f) else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelect(session) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (session.id == currentSessionId) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Accent),
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "$count ${if (count == 1) "message" else "messages"} \u00B7 " +
                                    formatHistoryTime(session.updatedAt),
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = "\u00D7",
                            color = TextSecondary,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onDelete(session.id) }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .semantics { contentDescription = "Delete chat" },
                        )
                    }
                    if (index != sessions.lastIndex) {
                        HorizontalDivider(
                            color = Border.copy(alpha = 0.7f),
                            thickness = 0.75.dp,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private val HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, HH:mm")

private fun formatHistoryTime(timestamp: Long): String =
    HISTORY_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

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
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
            message.proactive -> "MERO AGENT \u00B7 DAILY SUMMARY"
            message.agent != null -> "MERO AGENT \u00B7 AGENT"
            else -> null
        }

        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .then(
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
                        .fillMaxWidth()
                        .then(if (dark) Modifier else Modifier.shadow(1.dp, UmeShape))
                        .clip(UmeShape)
                        .background(bubbleColor)
                        .border(
                            1.dp,
                            if (message.proactive) WarmAccent else bubbleBorder,
                            UmeShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (message.agent != null) {
                        AgentStatusCard(message.agent, expanded = agentExpanded)
                        if (message.webSources.isNotEmpty()) {
                            WebSourcesBadge(message.webSources, onOpenWebSources)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatChatTime(message.createdAt),
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.width(5.dp))
                            CopyReplyButton(message.text)
                        }
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
                            if (message.text.isBlank() && message.streaming) {
                                Text(
                                    text = content,
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            } else {
                                MarkdownText(
                                    markdown = content,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = formatChatTime(message.createdAt),
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                            )
                            Spacer(Modifier.width(5.dp))
                            CopyReplyButton(message.text)
                        }
                    }
                }
            }
        }
    }

@Composable
private fun CopyReplyButton(rawText: String) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val accent = Accent
    val secondary = TextSecondary
    var copied by remember(rawText) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                clipboard.setText(AnnotatedString(rawText))
                copied = true
            }
            .semantics { contentDescription = if (copied) "Copied" else "Copy reply" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val stroke = 1.2.dp.toPx()
            if (copied) {
                drawLine(
                    color = accent,
                    start = Offset(size.width * 0.15f, size.height * 0.55f),
                    end = Offset(size.width * 0.42f, size.height * 0.8f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = accent,
                    start = Offset(size.width * 0.42f, size.height * 0.8f),
                    end = Offset(size.width * 0.86f, size.height * 0.22f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                drawRoundRect(
                    color = secondary,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.28f),
                    size = Size(size.width * 0.62f, size.height * 0.62f),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    color = secondary,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.1f),
                    size = Size(size.width * 0.62f, size.height * 0.62f),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(stroke),
                )
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
private fun VisionSheetOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Elevated)
            .border(0.75.dp, Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Text("\u203A", color = TextSecondary, fontSize = 20.sp)
    }
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
        val bitmap = remember(attachment.imagePath) {
            attachment.imagePath?.let { BitmapFactory.decodeFile(it) }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Attached image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            AttachmentTypeBadge(attachment.kind, Color.Transparent, TextSecondary)
        }
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
    val bitmap = remember(attachment.imagePath) {
        attachment.imagePath?.let { BitmapFactory.decodeFile(it) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Sent image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(190.dp)
                .height(128.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
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
                bottom = 6.dp,
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
                                text = if (generating) "Generating..." else "Message Mero Agent",
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
