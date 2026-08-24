package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private sealed interface NoteLine {
    data class Heading(val text: String) : NoteLine
    data class Bullet(val text: String) : NoteLine
    data class Paragraph(val text: String) : NoteLine
}

private fun parseNotes(raw: String): List<NoteLine> {
    val lines = mutableListOf<NoteLine>()
    val paragraph = StringBuilder()

    fun flush() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) lines += NoteLine.Paragraph(text)
        paragraph.setLength(0)
    }

    raw.lines().forEach { line ->
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> flush()

            trimmed.startsWith("#") -> {
                flush()
                lines += NoteLine.Heading(trimmed.trimStart('#').trim())
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush()
                lines += NoteLine.Bullet(trimmed.drop(2).trim())
            }

            line.startsWith("  ") && lines.lastOrNull() is NoteLine.Bullet -> {
                val last = lines.removeAt(lines.lastIndex) as NoteLine.Bullet
                lines += NoteLine.Bullet(last.text + " " + trimmed)
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }

    flush()

    return lines
}

@Composable
fun ReleaseNotes(raw: String, modifier: Modifier = Modifier) {
    val lines = remember(raw) { parseNotes(raw) }

    Column(modifier = modifier) {
        lines.forEachIndexed { index, line ->
            when (line) {
                is NoteLine.Heading -> {
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                is NoteLine.Bullet -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is NoteLine.Paragraph -> {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
