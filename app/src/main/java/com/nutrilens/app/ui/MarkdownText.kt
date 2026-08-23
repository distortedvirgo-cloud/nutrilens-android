package com.nutrilens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Лёгкий рендерер подмножества Markdown, которое возвращают ИИ-инструменты:
 * заголовки #/##/###, списки «-» и «1.», жирный **…**, курсив *…*, абзацы.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = text.replace("\r\n", "\n").split("\n")
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (line in blocks) {
            when {
                line.isBlank() -> androidx.compose.foundation.layout.Spacer(
                    Modifier.height(4.dp)
                )
                line.startsWith("### ") -> MdHeading(line.substring(4), 3)
                line.startsWith("## ") -> MdHeading(line.substring(3), 2)
                line.startsWith("# ") -> MdHeading(line.substring(2), 1)
                line.startsWith("- ") || line.startsWith("• ") -> MdBullet(line.substring(2))
                line.matches(Regex("^\\d+[.)]\\s.*")) -> MdBullet(line.replaceFirst(Regex("^\\d+[.)]\\s"), ""), numbered = true)
                else -> MdParagraph(line)
            }
        }
    }
}

@Composable
private fun MdHeading(text: String, level: Int) {
    val style = when (level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    androidx.compose.material3.Text(
        text = text,
        style = style,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun MdBullet(text: String, numbered: Boolean = false) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        androidx.compose.material3.Text(
            text = if (numbered) "•  " else "•  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        androidx.compose.material3.Text(
            text = inlineMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MdParagraph(text: String) {
    androidx.compose.material3.Text(
        text = inlineMarkdown(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** Жирный **…**, курсив *…*, инлайн-код `…`. */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    val inner = text.substring(i + 2, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inner) }
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append("`")
                    i++
                }
            }
            text.startsWith("*", i) && !text.startsWith("**", i) -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append("*")
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
