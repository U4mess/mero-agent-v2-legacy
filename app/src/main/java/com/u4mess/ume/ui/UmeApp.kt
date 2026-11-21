package com.u4mess.ume.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.brain.ChatController
import com.u4mess.ume.voice.VoiceChatSession
import kotlinx.coroutines.flow.collect

private enum class Tab(val label: String) {
    Chat("Chat"),
    Memory("Memory"),
    Models("Models"),
    Settings("Settings"),
}

private val DockBackground = Color(0xFF18181A)
private val DockBorder = Color(0xFF2A2826)
private val DockText = Color(0xFFF5F1EA)
private val DockMuted = Color(0xFF8E8A84)
private val DockAskSelected = Color(0xFF2C2924)
private val DockTabSelected = Color(0xFF242326)

@Composable
fun UmeApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }
    val generating by ChatController.generating.collectAsState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val layoutDirection = LocalLayoutDirection.current

    LaunchedEffect(Unit) {
        VoiceChatSession.openRequests.collect { request ->
            if (request > 0) tab = Tab.Chat
        }
    }

    LaunchedEffect(Unit) {
        ChatFocusRequest.requests.collect { request ->
            if (request > 0) tab = Tab.Chat
        }
    }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            if (!imeVisible) {
                FloatingDock(
                    selectedTab = tab,
                    generating = generating,
                    onAsk = {
                        tab = Tab.Chat
                        ChatFocusRequest.request()
                    },
                    onSelect = { tab = it },
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = if (imeVisible) 0.dp else innerPadding.calculateBottomPadding(),
                ),
        ) {
            when (tab) {
                Tab.Chat -> ChatScreen()
                Tab.Memory -> MemoryScreen()
                Tab.Models -> ModelsScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun FloatingDock(
    selectedTab: Tab,
    generating: Boolean,
    onAsk: () -> Unit,
    onSelect: (Tab) -> Unit,
) {
    val shape = RoundedCornerShape(30.dp)
    val askColor by animateColorAsState(
        targetValue = if (selectedTab == Tab.Chat) DockAskSelected else Color.Transparent,
        animationSpec = tween(220),
        label = "dockAsk",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(18.dp, shape)
                .clip(shape)
                .background(DockBackground)
                .border(0.75.dp, DockBorder, shape)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(askColor)
                    .clickable(onClick = onAsk)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UmeMonkeyFace(
                    modifier = Modifier.size(26.dp),
                    mood = if (generating) MonkeyMood.THINKING else MonkeyMood.IDLE,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "Ask",
                    color = DockText,
                    fontFamily = UmeSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(Tab.Memory, Tab.Models, Tab.Settings).forEach { entry ->
                    DockTab(entry, selectedTab == entry) { onSelect(entry) }
                }
            }
        }
    }
}

@Composable
private fun DockTab(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) DockText else DockMuted
    val highlight by animateColorAsState(
        targetValue = if (selected) DockTabSelected else Color.Transparent,
        animationSpec = tween(220),
        label = "dockTab",
    )

    Column(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(highlight)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DockIcon(tab, tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = tab.label,
            color = tint,
            fontSize = 9.sp,
            letterSpacing = 0.2.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun DockIcon(tab: Tab, tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.3.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        when (tab) {
            Tab.Memory -> {
                for (i in 0..2) {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.16f, h * (0.22f + i * 0.2f)),
                        size = Size(w * 0.68f, h * 0.12f),
                        cornerRadius = CornerRadius(h * 0.06f),
                        style = stroke,
                    )
                }
            }

            Tab.Models -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.2f, h * 0.2f),
                    size = Size(w * 0.6f, h * 0.6f),
                    cornerRadius = CornerRadius(w * 0.16f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.38f, h * 0.38f),
                    size = Size(w * 0.24f, h * 0.24f),
                    cornerRadius = CornerRadius(w * 0.06f),
                    style = stroke,
                )
            }

            Tab.Settings -> {
                for (i in 0..2) {
                    val y = h * (0.3f + i * 0.2f)
                    drawLine(
                        color = tint,
                        start = Offset(w * 0.16f, y),
                        end = Offset(w * 0.84f, y),
                        strokeWidth = 1.3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = tint,
                        radius = w * 0.075f,
                        center = Offset(if (i == 1) w * 0.36f else w * 0.64f, y),
                    )
                }
            }

            Tab.Chat -> {
                val path = Path().apply {
                    moveTo(w * 0.2f, h * 0.24f)
                    lineTo(w * 0.8f, h * 0.24f)
                    lineTo(w * 0.8f, h * 0.68f)
                    lineTo(w * 0.46f, h * 0.68f)
                    lineTo(w * 0.3f, h * 0.84f)
                    lineTo(w * 0.3f, h * 0.68f)
                    lineTo(w * 0.2f, h * 0.68f)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
        }
    }
}
