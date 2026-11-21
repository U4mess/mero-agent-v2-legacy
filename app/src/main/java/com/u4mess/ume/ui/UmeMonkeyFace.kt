package com.u4mess.ume.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay

enum class MonkeyMood { IDLE, THINKING, ACTING, CHARGING }

private val Fur = Color(0xFFA9714B)
private val FurShadow = Color(0xFF8C5A3B)
private val InnerEar = Color(0xFFD79A6C)
private val FaceCream = Color(0xFFF3DCC3)
private val Muzzle = Color(0xFFF8E9D6)
private val Ink = Color(0xFF3A2418)
private val Blush = Color(0xFFE8A07A)
private val amberPulse = Color(0xFFF0B35A)

/**
 * A small hand-drawn monkey that stays legible from the 20.dp island face up to
 * the 48–64.dp hero cards. Moods drive subtle motion, not layout.
 */
@Composable
fun UmeMonkeyFace(
    modifier: Modifier = Modifier,
    mood: MonkeyMood = MonkeyMood.IDLE,
) {
    var blinking by remember { mutableStateOf(false) }

    LaunchedEffect(mood) {
        blinking = false
        if (mood != MonkeyMood.IDLE) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(3_000L, 5_000L))
            blinking = true
            delay(130L)
            blinking = false
        }
    }

    val eyeOpen by animateFloatAsState(
        targetValue = if (blinking) 0f else 1f,
        animationSpec = tween(90),
        label = "monkeyBlink",
    )

    val motion = rememberInfiniteTransition(label = "monkeyMotion")
    val look by motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "monkeyLook",
    )
    val bob by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "monkeyBob",
    )
    val pulse by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "monkeyPulse",
    )

    Canvas(modifier) {
        val s = min(size.width, size.height)
        if (s <= 0f) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val bobOffset = if (mood == MonkeyMood.THINKING) (bob - 0.5f) * s * 0.035f else 0f
        val center = Offset(cx, cy + bobOffset)

        if (mood == MonkeyMood.ACTING) {
            drawCircle(
                color = amberPulse.copy(alpha = 0.14f + 0.16f * (1f - pulse)),
                radius = s * (0.40f + 0.07f * pulse),
                center = center,
            )
        }

        // Rounded ears behind the head.
        drawCircle(FurShadow, s * 0.155f, Offset(center.x - s * 0.33f, center.y - s * 0.03f))
        drawCircle(FurShadow, s * 0.155f, Offset(center.x + s * 0.33f, center.y - s * 0.03f))
        drawCircle(InnerEar, s * 0.105f, Offset(center.x - s * 0.33f, center.y - s * 0.03f))
        drawCircle(InnerEar, s * 0.105f, Offset(center.x + s * 0.33f, center.y - s * 0.03f))

        // Head and cream face mask.
        drawCircle(Fur, s * 0.40f, center)
        drawOval(
            color = FaceCream,
            topLeft = Offset(center.x - s * 0.305f, center.y - s * 0.285f),
            size = Size(s * 0.61f, s * 0.58f),
        )

        // Muzzle.
        drawOval(
            color = Muzzle,
            topLeft = Offset(center.x - s * 0.22f, center.y + s * 0.015f),
            size = Size(s * 0.44f, s * 0.30f),
        )

        // Small tuft.
        drawCircle(Fur, s * 0.055f, Offset(center.x, center.y - s * 0.395f))

        // Eyes.
        val eyeY = center.y - s * 0.075f
        val eyeDx = s * 0.15f
        val lookX = when (mood) {
            MonkeyMood.THINKING -> look * s * 0.035f
            MonkeyMood.ACTING -> 0f
            else -> 0f
        }
        val closed = mood == MonkeyMood.CHARGING || eyeOpen < 0.2f

        if (closed) {
            val arcSize = Size(s * 0.13f, s * 0.09f)
            drawArc(
                color = Ink,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(center.x - eyeDx - arcSize.width / 2f, eyeY - arcSize.height / 2f),
                size = arcSize,
                style = Stroke(width = s * 0.032f, cap = StrokeCap.Round),
            )
            drawArc(
                color = Ink,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(center.x + eyeDx - arcSize.width / 2f, eyeY - arcSize.height / 2f),
                size = arcSize,
                style = Stroke(width = s * 0.032f, cap = StrokeCap.Round),
            )
        } else {
            val eyeWidth = if (mood == MonkeyMood.ACTING) s * 0.125f else s * 0.105f
            val eyeHeight = (if (mood == MonkeyMood.ACTING) s * 0.155f else s * 0.115f) * eyeOpen
            for (side in listOf(-1f, 1f)) {
                val eyeX = center.x + side * eyeDx + lookX
                drawOval(
                    color = Ink,
                    topLeft = Offset(eyeX - eyeWidth / 2f, eyeY - eyeHeight / 2f),
                    size = Size(eyeWidth, eyeHeight),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = s * 0.018f,
                    center = Offset(eyeX - eyeWidth * 0.16f, eyeY - eyeHeight * 0.22f),
                )
            }
        }

        // Nose.
        drawOval(
            color = Ink,
            topLeft = Offset(center.x - s * 0.035f, center.y + s * 0.065f),
            size = Size(s * 0.07f, s * 0.045f),
        )

        // Smile.
        drawArc(
            color = Ink,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(center.x - s * 0.12f, center.y + s * 0.095f),
            size = Size(s * 0.24f, s * 0.16f),
            style = Stroke(width = s * 0.03f, cap = StrokeCap.Round),
        )

        // Cheeks.
        drawCircle(Blush.copy(alpha = 0.28f), s * 0.055f, Offset(center.x - s * 0.235f, center.y + s * 0.115f))
        drawCircle(Blush.copy(alpha = 0.28f), s * 0.055f, Offset(center.x + s * 0.235f, center.y + s * 0.115f))
    }
}
