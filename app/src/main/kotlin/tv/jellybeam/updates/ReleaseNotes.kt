package tv.jellybeam.updates

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val headingLine = Regex("#{1,6} .*")
internal data class NoteBlock(val heading: Boolean, val text: AnnotatedString)
internal fun noteBlocks(notes: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    var paragraph = StringBuilder()
    fun flush() { if (paragraph.isNotEmpty()) { blocks += NoteBlock(false, noteInline(paragraph.toString())); paragraph = StringBuilder() } }
    for (line in notes.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            headingLine.matches(trimmed) -> { flush(); blocks += NoteBlock(true, noteInline(trimmed.trimStart('#').trim())) }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> { flush(); blocks += NoteBlock(false, noteInline("• " + trimmed.drop(2))) }
            else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(trimmed) }
        }
    }
    flush()
    return blocks
}
// Emphasis needs word boundaries and no inner edge space, so snake_case and "2 * 3" stay literal.
private val noteTokens = Regex("`[^`]+`|\\*\\*[^*]+\\*\\*" +
    "|(?<![\\p{L}\\p{N}*])\\*[^*\\s](?:[^*]*[^*\\s])?\\*(?![\\p{L}\\p{N}*])" +
    "|(?<![\\p{L}\\p{N}_])_[^_\\s](?:[^_]*[^_\\s])?_(?![\\p{L}\\p{N}_])" +
    "|\\[[^]]+]\\([^)]*\\)")
internal fun noteInline(raw: String): AnnotatedString {
    val text = raw.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "").replace(Regex("<[^>]*>"), "")
    val tokens = noteTokens
    return buildAnnotatedString {
        var pos = 0
        for (match in tokens.findAll(text)) {
            append(text.substring(pos, match.range.first))
            val token = match.value
            when {
                token.startsWith('`') -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(token.drop(1).dropLast(1)) }
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
                token.startsWith('[') -> append(token.substringAfter('[').substringBefore(']'))
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.drop(1).dropLast(1)) }
            }
            pos = match.range.last + 1
        }
        append(text.substring(pos))
    }
}
