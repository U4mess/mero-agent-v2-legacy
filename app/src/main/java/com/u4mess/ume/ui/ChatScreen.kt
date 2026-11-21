package com.u4mess.ume.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.brain.ChatRole

@Composable
fun ChatScreen() {
    val messages by ChatController.messages.collectAsState()
    val generating by ChatController.generating.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        ScreenHeader("Chat") {
            if (generating) {
                Text(
                    text = "Stop",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Elevated)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickable { ChatController.stop() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }

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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }

        ChatInput(
            value = input,
            onValueChange = { input = it },
            generating = generating,
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
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
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
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = if (generating) "Generating…" else "Message Ume",
                                color = TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Send",
                color = if (value.isNotBlank() && !generating) Black else TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (value.isNotBlank() && !generating) TextPrimary else Border)
                    .clickable(enabled = value.isNotBlank() && !generating, onClick = onSend)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatController.Message) {
    if (message.role == ChatRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = message.text,
                color = TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Elevated)
                    .border(1.dp, Border, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "UME",
                color = TextSecondary,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            val content = when {
                message.text.isBlank() && message.streaming -> "Thinking…"
                message.streaming -> message.text + "▍"
                else -> message.text
            }
            Text(
                text = content,
                color = if (message.text.isBlank() && message.streaming) TextSecondary else TextPrimary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
    }
}
