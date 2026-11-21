package com.u4mess.ume.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceMicButton(
    isListening: Boolean,
    rmsLevel: Float,
    onClick: () -> Unit,
    tint: Color,
    border: Color,
    modifier: Modifier = Modifier,
) {
    val level by animateFloatAsState(
        targetValue = if (isListening) rmsLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(120),
        label = "micRms",
    )
    val active = Color(0xFFE0A458)

    Box(
        modifier = modifier
            .size(32.dp)
            .scale(1f + level * 0.10f)
            .clip(CircleShape)
            .background(if (isListening) active.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (isListening) active else border, CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (isListening) "Stop voice input" else "Voice input"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            val w = size.width
            val h = size.height
            val color = if (isListening) active else tint
            val stroke = Stroke(
                width = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )

            if (isListening) {
                drawCircle(
                    color = active.copy(alpha = 0.22f + 0.2f * level),
                    radius = w * (0.55f + 0.12f * level),
                )
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.38f, h * 0.12f),
                size = Size(w * 0.24f, h * 0.42f),
                cornerRadius = CornerRadius(w * 0.12f),
                style = stroke,
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w * 0.26f, h * 0.28f),
                size = Size(w * 0.48f, h * 0.40f),
                style = stroke,
            )
            drawLine(
                color = color,
                start = Offset(w * 0.5f, h * 0.68f),
                end = Offset(w * 0.5f, h * 0.84f),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(w * 0.34f, h * 0.90f),
                end = Offset(w * 0.66f, h * 0.90f),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
