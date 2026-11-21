package com.u4mess.ume.service

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.ui.MonkeyMood
import com.u4mess.ume.ui.UmeMonkeyFace
import com.u4mess.ume.ui.VoiceMicButton

private val IslandBackground = Color(0xFF000000)
private val IslandBorder = Color(0xFF1F1F1F)
private val IslandText = Color(0xFFFFFFFF)
private val IslandTextSecondary = Color(0xFFA0A0A0)

@Composable
internal fun DynamicIsland(
    expanded: Boolean,
    agentStatus: AgentStatus?,
    approval: ApprovalRequest?,
    isListening: Boolean,
    partialTranscript: String,
    rmsLevel: Float,
    onMicClick: () -> Unit,
    onTalkClick: () -> Unit,
    onApprove: () -> Unit,
    onCancelApproval: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onSend: (String) -> Unit,
    onStopAgent: () -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val idleWidth = IslandSettings.widthDp.collectAsState().value
    val statusMode = agentStatus != null
    val statusMood = when {
        agentStatus == null -> MonkeyMood.IDLE
        agentStatus.active && agentStatus.text.contains("Thinking", ignoreCase = true) -> MonkeyMood.THINKING
        agentStatus.active -> MonkeyMood.ACTING
        else -> MonkeyMood.IDLE
    }

    val width by animateDpAsState(
        targetValue = when {
            statusMode -> (screenWidth - 72).dp
            expanded -> (screenWidth - 32).dp
            else -> idleWidth.dp
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "islandWidth",
    )
    val height by animateDpAsState(
        targetValue = when {
            statusMode -> 40.dp
            expanded -> 52.dp
            else -> 24.dp
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "islandHeight",
    )
    val corner by animateDpAsState(
        targetValue = when {
            statusMode -> 20.dp
            expanded -> 26.dp
            else -> 12.dp
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "islandCorner",
    )

    LaunchedEffect(expanded, statusMode) {
        if (expanded && !statusMode) {
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
            if (!expanded) prompt = ""
        }
    }

    LaunchedEffect(isListening, partialTranscript) {
        if (isListening && partialTranscript.isNotBlank()) {
            prompt = partialTranscript
        }
    }

    LaunchedEffect(statusMode) {
        if (statusMode && expanded) onCollapse()
    }

    fun send() {
        val text = prompt.trim()
        if (text.isNotEmpty()) {
            onSend(text)
            prompt = ""
        }
    }

    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(IslandBackground)
            .border(1.dp, IslandBorder, RoundedCornerShape(corner))
            .then(
                if (expanded || statusMode) {
                    Modifier
                } else {
                    Modifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Open Mero Agent",
                            onClick = onExpand,
                        )
                        .semantics { contentDescription = "Mero Agent" }
                },
            )
            .padding(
                start = 12.dp,
                end = if (agentStatus == null && !expanded) 4.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (agentStatus == null && !expanded) {
            Arrangement.End
        } else {
            Arrangement.Start
        },
    ) {
        if (agentStatus != null) {
            UmeMonkeyFace(
                Modifier.size(22.dp),
                mood = if (approval != null) MonkeyMood.ACTING else statusMood,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = approval?.prompt ?: agentStatus.text,
                color = IslandText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (approval != null) {
                Spacer(Modifier.width(6.dp))
                IslandAction("Approve", filled = true, onClick = onApprove)
                Spacer(Modifier.width(4.dp))
                IslandAction("Cancel", filled = false, onClick = onCancelApproval)
            } else if (agentStatus.active) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Stop",
                    color = IslandBackground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(IslandText)
                        .clickable(onClick = onStopAgent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        } else if (expanded) {
            UmeMonkeyFace(
                Modifier
                    .size(22.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onTalkClick() })
                    }
                    .semantics { contentDescription = "Long press for Talk mode" },
                mood = if (isListening) MonkeyMood.ACTING else MonkeyMood.THINKING,
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = true,
                textStyle = TextStyle(color = IslandText, fontSize = 14.sp),
                cursorBrush = SolidColor(IslandText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box {
                        if (prompt.isEmpty()) {
                            Text("Ask Mero Agent...", color = IslandTextSecondary, fontSize = 14.sp)
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
                tint = IslandText,
                border = IslandBorder,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(IslandText)
                    .clickable(onClick = ::send)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Send", color = IslandBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, IslandBorder, CircleShape)
                    .clickable(onClick = onCollapse)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                Text("\u00D7", color = IslandTextSecondary, fontSize = 16.sp)
            }
        } else {
            UmeMonkeyFace(Modifier.size(20.dp), mood = MonkeyMood.IDLE)
        }
    }
}

@Composable
private fun IslandAction(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (filled) IslandBackground else IslandText,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) IslandText else Color.Transparent)
            .border(1.dp, if (filled) IslandText else IslandBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

