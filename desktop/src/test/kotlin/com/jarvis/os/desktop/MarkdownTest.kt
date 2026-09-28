package com.jarvis.os.desktop

import com.jarvis.os.desktop.Markdown.Block
import com.jarvis.os.desktop.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    @Test
    fun plainTextIsOneParagraph() {
        assertEquals(listOf(Block.Paragraph(listOf(Span("Hello there.")))), Markdown.parse("Hello there."))
    }

    @Test
    fun softLineBreaksJoinAndBlankLinesSplitParagraphs() {
        val blocks = Markdown.parse("one\ntwo\n\nthree")
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("one two"))), Block.Paragraph(listOf(Span("three")))),
            blocks,
        )
    }

    @Test
    fun headingsBulletsNumbersAndQuotes() {
        val blocks = Markdown.parse("## Plan\n- first\n* second\n1. one\n2) two\n> note")
        assertEquals(Block.Heading(2, listOf(Span("Plan"))), blocks[0])
        assertEquals(Block.Bullet(listOf(Span("first"))), blocks[1])
        assertEquals(Block.Bullet(listOf(Span("second"))), blocks[2])
        assertEquals(Block.Numbered(1, listOf(Span("one"))), blocks[3])
        assertEquals(Block.Numbered(2, listOf(Span("two"))), blocks[4])
        assertEquals(Block.Quote(listOf(Span("note"))), blocks[5])
    }

    @Test
    fun nestedBulletsCarryDepth() {
        assertEquals(1, (Markdown.parse("  - inner")[0] as Block.Bullet).depth)
    }

    @Test
    fun fencedCodeIsKeptVerbatim() {
        val blocks = Markdown.parse("Run:\n```\n./gradlew **not bold**\n  indented\n```\nDone.")
        assertEquals(Block.Code("./gradlew **not bold**\n  indented"), blocks[1])
        assertEquals(Block.Paragraph(listOf(Span("Done."))), blocks[2])
    }

    @Test
    fun unterminatedFenceTakesTheRest() {
        assertEquals(listOf(Block.Code("a\nb")), Markdown.parse("```\na\nb"))
    }

    @Test
    fun inlineBoldItalicAndCode() {
        assertEquals(
            listOf(Span("a "), Span("bold", bold = true), Span(" and "), Span("it", italic = true), Span(" and "), Span("x()", code = true)),
            Markdown.inline("a **bold** and *it* and `x()`"),
        )
    }

    @Test
    fun unclosedMarkersStayLiteral() {
        assertEquals(listOf(Span("2 ** 3 and `tick")), Markdown.inline("2 ** 3 and `tick"))
    }

    @Test
    fun arithmeticAndSnakeCaseAreNotItalic() {
        assertEquals(listOf(Span("5 * 3 = 15")), Markdown.inline("5 * 3 = 15"))
        assertEquals(listOf(Span("use my_var_name here")), Markdown.inline("use my_var_name here"))
    }

    @Test
    fun boldInsideAListItem() {
        assertEquals(
            Block.Numbered(1, listOf(Span("8:30", bold = true), Span(" — deep work"))),
            Markdown.parse("1. **8:30** — deep work")[0],
        )
    }
}
