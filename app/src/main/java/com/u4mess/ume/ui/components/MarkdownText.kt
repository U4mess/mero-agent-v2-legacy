package com.u4mess.ume.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u4mess.ume.ui.Accent
import com.u4mess.ume.ui.Border
import com.u4mess.ume.ui.TextPrimary
import com.u4mess.ume.ui.TextSecondary
import kotlinx.coroutines.delay

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Numbered(val number: String, val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    color = color,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )

                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    color = color,
                    fontSize = when (block.level) {
                        1 -> 17.sp
                        2 -> 15.sp
                        else -> 14.sp
                    },
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )

                is MarkdownBlock.Bullet -> ListRow(marker = "\u2022", text = block.text, color = color)
                is MarkdownBlock.Numbered -> ListRow(marker = block.number, text = block.text, color = color)
                is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, text: String, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = inlineMarkdown(text),
            color = color,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    UmeCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        cornerRadius = 12.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "CODE" }.uppercase(),
                color = TextSecondary,
                fontSize = 9.sp,
                letterSpacing = 0.7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (copied) "Copied" else "Copy",
                color = if (copied) Accent else TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = code,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                softWrap = false,
            )
        }
    }
}

private fun parseMarkdown(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()
    val lines = source.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> index++
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(language, code.toString())
            }
            trimmed.startsWith("#") -> {
                val match = Regex("^(#{1,3})\\s+(.*)$").find(trimmed)
                if (match != null) {
                    blocks += MarkdownBlock.Heading(
                        level = match.groupValues[1].length,
                        text = match.groupValues[2],
                    )
                } else {
                    blocks += MarkdownBlock.Paragraph(trimmed)
                }
                index++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("\u2022 ") -> {
                blocks += MarkdownBlock.Bullet(trimmed.drop(2))
                index++
            }
            Regex("^\\d+\\.\\s+.*").matches(trimmed) -> {
                val number = trimmed.substringBefore('.').plus(".")
                val text = trimmed.substringAfter('.').trim()
                blocks += MarkdownBlock.Numbered(number, text)
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val current = lines[index].trim()
                    if (current.isEmpty() ||
                        current.startsWith("```") ||
                        current.startsWith("#") ||
                        current.startsWith("- ") ||
                        current.startsWith("* ") ||
                        Regex("^\\d+\\.\\s+.*").matches(current)
                    ) {
                        break
                    }
                    paragraph += current
                    index++
                }
                if (paragraph.isNotEmpty()) {
                    blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
                } else {
                    index++
                }
            }
        }
    }
    return blocks
}

@Composable
private fun inlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith("**", index) -> {
                val end = source.indexOf("**", index + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(source.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(source[index])
                    index++
                }
            }
            source[index] == '`' -> {
                val end = source.indexOf('`', index + 1)
                if (end >= 0) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Border.copy(alpha = 0.55f),
                            color = TextPrimary,
                        ),
                    ) {
                        append(source.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(source[index])
                    index++
                }
            }
            source[index] == '*' -> {
                val end = source.indexOf('*', index + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(source.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(source[index])
                    index++
                }
            }
            else -> {
                append(source[index])
                index++
            }
        }
    }
}
