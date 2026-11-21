package com.u4mess.ume.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ChatRole
import com.u4mess.ume.voice.VoiceChatSession
import com.u4mess.ume.voice.VoiceInputManager
import com.u4mess.ume.voice.VoiceOutputManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val VoiceAccent = Color(0xFFE0A458)
private val VoiceRed = Color(0xFFB3413A)

@Composable
fun VoiceChatSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listening by VoiceInputManager.isListening.collectAsState()
    val partial by VoiceInputManager.partialTranscript.collectAsState()
    val rms by VoiceInputManager.rmsLevel.collectAsState()
    val speaking by VoiceOutputManager.isSpeaking.collectAsState()
    val generating by ChatController.generating.collectAsState()
    val messages by ChatController.messages.collectAsState()
    val muted by VoiceChatSession.muted.collectAsState()
    var waitingForReply by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val submitVoice: (String) -> Unit = { text ->
        if (text.isNotBlank()) {
            waitingForReply = true
            ChatController.send(text)
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            if (VoiceChatSession.active.value && !VoiceChatSession.muted.value) {
                VoiceInputManager.startListening(submitVoice)
            }
        } else {
            permissionDenied = true
        }
    }

    val startListeningNow: () -> Unit = {
        if (VoiceChatSession.active.value && !VoiceChatSession.muted.value) {
            if (!VoiceInputManager.hasPermission(context)) {
                permissionDenied = true
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                permissionDenied = false
                if (!VoiceInputManager.isListening.value) {
                    VoiceInputManager.startListening(submitVoice)
                }
            }
        }
    }

    val resumeConversation: () -> Unit = {
        if (VoiceChatSession.active.value && !VoiceChatSession.muted.value && waitingForReply) {
            waitingForReply = false
            scope.launch {
                delay(400)
                if (VoiceChatSession.active.value && !VoiceChatSession.muted.value) {
                    startListeningNow()
                }
            }
        }
    }

    val interrupt: () -> Unit = {
        ChatController.stop()
        VoiceOutputManager.stop()
        waitingForReply = false
        if (!VoiceInputManager.isListening.value) startListeningNow()
    }

    val toggleMute: () -> Unit = {
        val next = !muted
        VoiceChatSession.setMuted(next)
        if (next) {
            VoiceOutputManager.stop()
            waitingForReply = false
        } else if (!VoiceInputManager.isListening.value && !generating) {
            startListeningNow()
        }
    }

    val end: () -> Unit = {
        ChatController.stop()
        VoiceInputManager.cancel()
        VoiceOutputManager.stop()
        VoiceChatSession.setActive(false)
        onDismiss()
    }

    DisposableEffect(Unit) {
        VoiceChatSession.setActive(true)
        VoiceChatSession.setMuted(false)
        VoiceOutputManager.onAllDone = { resumeConversation() }
        onDispose {
            VoiceOutputManager.onAllDone = null
            VoiceChatSession.setActive(false)
            ChatController.stop()
            VoiceOutputManager.stop()
            VoiceInputManager.cancel()
        }
    }

    LaunchedEffect(Unit) {
        if (!VoiceInputManager.hasPermission(context)) {
            permissionDenied = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListeningNow()
        }
    }

    LaunchedEffect(generating, speaking, waitingForReply) {
        if (waitingForReply && !generating && !speaking) {
            delay(100)
            resumeConversation()
        }
    }

    val lastUme = messages
        .lastOrNull { it.role == ChatRole.ASSISTANT && it.text.isNotBlank() }
        ?.text
        .orEmpty()
    val caption = when {
        permissionDenied -> "Grant microphone access to start talking."
        listening && partial.isNotBlank() -> partial
        listening -> "Listening..."
        speaking && lastUme.isNotBlank() -> lastUme
        generating -> "Ume is thinking..."
        lastUme.isNotBlank() -> lastUme
        else -> "Tap the monkey to start talking."
    }
    val status = when {
        muted -> "Muted"
        speaking -> "Ume is speaking"
        listening -> "Listening"
        generating -> "Thinking"
        else -> "Ready"
    }

    val motion = rememberInfiniteTransition(label = "voiceGlow")
    val glow by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "voiceGlowValue",
    )

    Dialog(
        onDismissRequest = end,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Talk to Ume",
                color = TextPrimary,
                fontFamily = UmeSerif,
                fontSize = 28.sp,
            )
            Text(
                text = status,
                color = TextSecondary,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier.size(190.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = this.center
                    if (speaking) {
                        drawCircle(
                            color = VoiceAccent.copy(alpha = 0.12f + 0.10f * glow),
                            radius = size.minDimension * (0.40f + 0.05f * glow),
                            center = center,
                        )
                    }
                    if (listening) {
                        drawCircle(
                            color = VoiceAccent.copy(alpha = 0.18f + 0.10f * rms),
                            radius = size.minDimension * (0.36f + 0.10f * rms),
                            center = center,
                        )
                    }
                    drawCircle(
                        color = VoiceAccent.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.33f,
                        center = center,
                    )
                }
                UmeMonkeyFace(
                    modifier = Modifier
                        .size(110.dp)
                        .clickable {
                            if (speaking || generating) {
                                interrupt()
                            } else if (!VoiceInputManager.isListening.value) {
                                startListeningNow()
                            }
                        }
                        .semantics { contentDescription = "Talk to Ume" },
                    mood = when {
                        listening -> MonkeyMood.ACTING
                        generating -> MonkeyMood.THINKING
                        else -> MonkeyMood.IDLE
                    },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = caption,
                color = TextPrimary,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (permissionDenied) {
                TextButton(onClick = {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text("Grant microphone", color = VoiceAccent, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceControl(
                    label = if (muted) "Unmute" else "Mute",
                    onClick = toggleMute,
                )
                VoiceControl(
                    label = "Tap to interrupt",
                    onClick = interrupt,
                    highlighted = speaking || generating,
                )
                VoiceControl(
                    label = "End",
                    onClick = end,
                    danger = true,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Speak naturally. Ume listens, answers, then listens again.",
                color = TextSecondary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VoiceControl(
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    danger: Boolean = false,
) {
    Text(
        text = label,
        color = if (danger) Color.White else if (highlighted) VoiceAccent else TextPrimary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    danger -> VoiceRed
                    highlighted -> VoiceAccent.copy(alpha = 0.14f)
                    else -> Elevated
                },
            )
            .border(
                1.dp,
                when {
                    danger -> VoiceRed
                    highlighted -> VoiceAccent
                    else -> Border
                },
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}
