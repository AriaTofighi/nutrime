package com.nutrime.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nutrime.app.ui.theme.DesignTokens.Space

/** The small subset of Markdown the assistant writes: headings, bullet and numbered lists, bold, italic, code. */
sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val level: Int) : MdBlock
}

fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    for (raw in src.lines()) {
        val indent = raw.length - raw.trimStart().length
        val line = raw.trim()
        val bullet = Regex("^[-*•+]\\s+(.*)").find(line)
        val numbered = Regex("^(\\d+)[.)]\\s+(.*)").find(line)
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> { flush(); out += MdBlock.Heading(line.trimStart('#').trim()) }
            bullet != null -> { flush(); out += MdBlock.Item("•", bullet.groupValues[1], indent / 2) }
            numbered != null -> { flush(); out += MdBlock.Item("${numbered.groupValues[1]}.", numbered.groupValues[2], indent / 2) }
            Regex("^-{3,}$").matches(line) -> flush()
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
        }
    }
    flush()
    return out
}

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")

fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(i, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[3]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4].ifEmpty { g[5] }) }
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

@Composable
fun Markdown(text: String, style: TextStyle, color: Color, codeBackground: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.S2)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(inlineMarkdown(b.text, codeBackground), style = style.copy(fontWeight = FontWeight.SemiBold), color = color)
                is MdBlock.Paragraph -> Text(inlineMarkdown(b.text, codeBackground), style = style, color = color)
                is MdBlock.Item -> Row(Modifier) {
                    Text(b.marker, style = style, color = color, modifier = Modifier.width(18.dp + (b.level * 14).dp))
                    Text(inlineMarkdown(b.text, codeBackground), style = style, color = color)
                }
            }
        }
    }
}
