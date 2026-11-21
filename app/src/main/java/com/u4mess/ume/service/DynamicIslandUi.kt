package com.u4mess.ume.service

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
import com.u4mess.ume.ui.Black
import com.u4mess.ume.ui.Border
import com.u4mess.ume.ui.TextPrimary
import com.u4mess.ume.ui.TextSecondary

@Composable
internal fun DynamicIsland(
    expanded: Boolean,
    agentStatus: AgentStatus?,
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
    val statusMode = agentStatus != null

    val width by animateDpAsState(
        targetValue = when {
            statusMode -> (screenWidth - 72).dp
            expanded -> (screenWidth - 32).dp
            else -> 110.dp
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "islandWidth",
    )
    val height by animateDpAsState(
        targetValue = when {
            statusMode -> 40.dp
            expanded -> 52.dp
            else -> 28.dp
        },
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "islandHeight",
    )
    val corner by animateDpAsState(
        targetValue = when {
            statusMode -> 20.dp
            expanded -> 26.dp
            else -> 14.dp
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
            .background(Black)
            .border(1.dp, Border, RoundedCornerShape(corner))
            .then(
                if (expanded || statusMode) {
                    Modifier
                } else {
                    Modifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Open Ume",
                            onClick = onExpand,
                        )
                        .semantics { contentDescription = "Ume" }
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (agentStatus != null) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(TextPrimary),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = agentStatus.text,
                color = TextPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (agentStatus.active) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Stop",
                    color = Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TextPrimary)
                        .clickable(onClick = onStopAgent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        } else if (expanded) {
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box {
                        if (prompt.isEmpty()) {
                            Text("Ask Ume…", color = TextSecondary, fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(TextPrimary)
                    .clickable(onClick = ::send)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Send", color = Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, Border, CircleShape)
                    .clickable(onClick = onCollapse)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = TextSecondary, fontSize = 16.sp)
            }
        } else {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(TextPrimary),
            )
            Spacer(Modifier.width(9.dp))
            Text("Ume", color = TextPrimary, fontSize = 12.sp, letterSpacing = 0.6.sp)
        }
    }
}
