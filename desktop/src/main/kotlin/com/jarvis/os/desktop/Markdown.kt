package com.jarvis.os.desktop

/**
 * Just enough Markdown to render a model reply the way an assistant should: headings,
 * bullet and numbered lists, fenced code, quotes, paragraphs, and inline **bold**,
 * *italic* and `code`. Pure (no Compose) so it is unit-tested (MarkdownTest); the
 * UI maps [Block]s and [Span]s onto styled text.
 *
 * Deliberately forgiving: anything it does not recognise is a paragraph, and an
 * unclosed `**` or backtick is left as literal text rather than swallowing the rest.
 */
object Markdown {

    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

    sealed interface Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Paragraph(val spans: List<Span>) : Block
        data class Bullet(val spans: List<Span>, val depth: Int = 0) : Block
        data class Numbered(val number: Int, val spans: List<Span>) : Block
        data class Quote(val spans: List<Span>) : Block
        data class Code(val text: String) : Block
    }

    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*•]\s+(.*)$""")
    private val NUMBERED = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
    private val QUOTE = Regex("""^>\s?(.*)$""")

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += Block.Paragraph(inline(para.toString().trim()))
            para.clear()
        }
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += Block.Code(code.toString())
                }
                trimmed.isEmpty() -> flush()
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.find(line)!!
                    out += Block.Bullet(inline(m.groupValues[2]), depth = (m.groupValues[1].length / 2).coerceAtMost(3))
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.find(line)!!
                    out += Block.Numbered(m.groupValues[1].toInt(), inline(m.groupValues[2]))
                }
                QUOTE.matches(trimmed) -> {
                    flush()
                    out += Block.Quote(inline(QUOTE.find(trimmed)!!.groupValues[1]))
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(trimmed)
                }
            }
            i++
        }
        flush()
        return out
    }

    /** Inline emphasis. Unclosed markers stay literal. */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun emit() {
            if (buf.isNotEmpty()) spans += Span(buf.toString(), bold = bold, italic = italic)
            buf.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        emit()
                        spans += Span(text.substring(i + 1, end), code = true)
                        i = end + 1
                        continue
                    }
                    buf.append(c)
                }
                text.startsWith("**", i) && (bold || text.indexOf("**", i + 2) > i + 2) -> {
                    emit(); bold = !bold; i += 2; continue
                }
                (c == '*' || c == '_') && isItalicMarker(text, i, italic) -> {
                    emit(); italic = !italic
                }
                else -> buf.append(c)
            }
            i++
        }
        emit()
        return mergeAdjacent(spans)
    }

    /**
     * A lone `*`/`_` toggles italic only when it opens against a word and has a
     * matching close later — so "5 * 3" and snake_case_names stay literal.
     */
    private fun isItalicMarker(text: String, i: Int, open: Boolean): Boolean {
        val c = text[i]
        if (text.getOrNull(i + 1) == c) return false
        if (open) return text.getOrNull(i - 1)?.isWhitespace() == false
        val next = text.getOrNull(i + 1) ?: return false
        if (next.isWhitespace()) return false
        if (c == '_' && text.getOrNull(i - 1)?.isLetterOrDigit() == true) return false
        return text.indexOf(c, i + 2) > i
    }

    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && last.bold == s.bold && last.italic == s.italic && last.code == s.code) {
                out[out.lastIndex] = last.copy(text = last.text + s.text)
            } else {
                out += s
            }
        }
        return out
    }
}
