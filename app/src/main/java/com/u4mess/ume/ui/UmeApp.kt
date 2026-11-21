package com.u4mess.ume.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Tab(val label: String) {
    Chat("Chat"),
    Memory("Memory"),
    Models("Models"),
    Settings("Settings"),
}

@Composable
fun UmeApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Chat) }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = Border, thickness = 1.dp)
                NavigationBar(containerColor = Black, tonalElevation = 0.dp) {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { TabIcon(entry, tab == entry) },
                            label = { Text(entry.label, fontSize = 11.sp, letterSpacing = 0.3.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                selectedTextColor = TextPrimary,
                                indicatorColor = Elevated,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
private fun TabIcon(tab: Tab, selected: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val tint = if (selected) TextPrimary else TextSecondary
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        when (tab) {
            Tab.Chat -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.18f),
                    size = Size(w * 0.76f, h * 0.5f),
                    cornerRadius = CornerRadius(w * 0.18f),
                    style = stroke,
                )
                val tail = Path().apply {
                    moveTo(w * 0.34f, h * 0.68f)
                    lineTo(w * 0.34f, h * 0.86f)
                    lineTo(w * 0.54f, h * 0.68f)
                }
                drawPath(tail, tint, style = stroke)
            }

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
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = tint,
                        radius = w * 0.08f,
                        center = Offset(if (i == 1) w * 0.36f else w * 0.64f, y),
                    )
                }
            }
        }
    }
}
