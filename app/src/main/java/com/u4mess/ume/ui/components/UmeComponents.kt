package com.u4mess.ume.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.u4mess.ume.ui.Accent
import com.u4mess.ume.ui.Border
import com.u4mess.ume.ui.Elevated
import com.u4mess.ume.ui.TextSecondary
import com.u4mess.ume.ui.UmeDark

@Composable
fun UmeCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .then(if (UmeDark) Modifier else Modifier.shadow(1.dp, shape))
            .clip(shape)
            .background(Elevated)
            .border(0.75.dp, Border, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun UmeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        color = TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Start,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UmeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val dark = UmeDark
    val accent = Accent
    val active = if (dark) accent else Color(0xFF18181A)
    val inactive = if (dark) Color(0xFF262422) else Color(0xFFE6E2DA)
    val thumbFill = if (dark) Color(0xFF181716) else Color(0xFFFFFFFF)

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(40.dp),
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(16.dp)
                    .then(if (dark) Modifier else Modifier.shadow(1.dp, CircleShape))
                    .clip(CircleShape)
                    .background(thumbFill)
                    .border(2.dp, active, CircleShape),
            )
        },
        track = { state ->
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            ) {
                val fraction = (
                    (state.value - valueRange.start) /
                        (valueRange.endInclusive - valueRange.start)
                    ).coerceIn(0f, 1f)
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    color = inactive,
                    size = Size(size.width, size.height),
                    cornerRadius = radius,
                )
                if (fraction > 0f) {
                    drawRoundRect(
                        color = active,
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = radius,
                    )
                }
            }
        },
    )
}
