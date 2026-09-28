package com.jarvis.os.desktop.knowledge

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocTextTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun pdf(vararg pages: String): File {
        val f = tmp.newFile("lease.pdf")
        PDDocument().use { doc ->
            for (text in pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    private fun docx(bodyXml: String): File {
        val f = tmp.newFile("notes.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(bodyXml.toByteArray())
            z.closeEntry()
        }
        return f
    }

    private fun expectRefusal(f: File, contains: String) {
        try {
            DocText.extract(f); fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    // ── PDF: real pages, real page numbers ──

    @Test
    fun pdfTextComesOutPageByPage() {
        val ex = DocText.extract(pdf("Rent is due on the 5th of each month.", "Notice period is two months."))
        assertEquals("page", ex.unit)
        assertEquals(listOf(1, 2), ex.pages.map { it.number })
        assertTrue(ex.pages[0].text.contains("Rent is due on the 5th"))
        assertTrue(ex.pages[1].text.contains("Notice period is two months"))
    }

    @Test
    fun aPdfWithNoTextLayerIsCalledAScan() {
        val f = tmp.newFile("scan.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(f) }
        expectRefusal(f, "scan")
    }

    // ── Word: StAX over document.xml, no library ──

    @Test
    fun docxParagraphsTabsAndBreaksSurvive() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>Action points</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Send the deck </w:t></w:r><w:r><w:t>to Priya</w:t></w:r></w:p>
            <w:p><w:r><w:t>Owner</w:t><w:tab/><w:t>Due</w:t><w:br/><w:t>next line</w:t></w:r></w:p>
            </w:body></w:document>"""
        val ex = DocText.extract(docx(xml))
        assertEquals("part", ex.unit)
        val text = ex.pages.single().text
        assertTrue(text, text.contains("Action points\nSend the deck to Priya\nOwner Due\nnext line"))
    }

    @Test
    fun docxNeverResolvesExternalEntities() {
        val secret = tmp.newFile("secret.txt").apply { writeText("TOP-SECRET-VALUE") }
        val xml = """<?xml version="1.0"?><!DOCTYPE w [<!ENTITY x SYSTEM "${secret.toURI()}">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>a &x; b</w:t></w:r></w:p></w:body></w:document>"""
        val out = runCatching { DocText.docxText(xml.byteInputStream()) }.getOrDefault("")
        assertFalse(out.contains("TOP-SECRET-VALUE"))
    }

    // ── text files ──

    @Test
    fun textFilesAreReadAndCutIntoParts() {
        val f = tmp.newFile("todo.txt").apply { writeText("﻿First line\n\nSecond paragraph") }
        val ex = DocText.extract(f)
        assertEquals("First line\n\nSecond paragraph", ex.pages.single().text)
    }

    @Test
    fun ansiFilesFromNotepadStillRead() {
        val f = tmp.newFile("ansi.txt").apply { writeBytes("Café menu".toByteArray(charset("windows-1252"))) }
        assertEquals("Café menu", DocText.extract(f).pages.single().text)
    }

    @Test
    fun savedWebPagesLoseTheirMarkup() {
        val f = tmp.newFile("page.html").apply { writeText("<html><style>p{}</style><script>var x=1</script><p>Hello &amp; welcome</p></html>") }
        assertEquals("Hello & welcome", DocText.extract(f).pages.single().text)
    }

    @Test
    fun unsupportedAndEmptyFilesSayWhy() {
        expectRefusal(tmp.newFile("old.doc"), ".docx or PDF")
        expectRefusal(tmp.newFile("movie.mkv"), "not .mkv")
        expectRefusal(tmp.newFile("empty.txt"), "empty")
        expectRefusal(File(tmp.root, "missing.pdf"), "can't find")
    }

    // ── parts, chunks, overview ──

    @Test
    fun partsBreakAtParagraphsAndAreNumbered() {
        val para = "word ".repeat(150).trim()           // ~750 chars
        val parts = DocText.parts(List(8) { para }.joinToString("\n\n"), size = 2_000)
        assertTrue(parts.size in 3..4)
        assertEquals((1..parts.size).toList(), parts.map { it.number })
        assertTrue(parts.all { it.text.length <= 2_000 })
        // No text lost at the cuts.
        assertEquals(8 * 150, parts.sumOf { p -> p.text.split(Regex("\\s+")).count { it == "word" } })
    }

    @Test
    fun chunksOverlapStayOnTheirPageAndCoverEverything() {
        val pages = listOf(DocText.Page(1, "alpha ".repeat(500).trim()), DocText.Page(2, "short page two"), DocText.Page(3, "  "))
        val chunks = DocText.chunks(pages, size = 1_000, overlap = 100)
        assertTrue(chunks.count { it.page == 1 } >= 3)
        assertEquals("short page two", chunks.single { it.page == 2 }.text)
        assertTrue(chunks.none { it.page == 3 })
        assertTrue(chunks.all { it.text.length <= 1_000 })
    }

    @Test
    fun overviewIsWholeWhenShortAndAnEvenSampleWhenLong() {
        val short = listOf(DocText.Page(1, "one"), DocText.Page(2, "two"))
        val (text, complete) = DocText.overview(short, "page", budget = 1_000)
        assertTrue(complete)
        assertEquals("[page 1]\none\n\n[page 2]\ntwo", text)

        val long = (1..40).map { DocText.Page(it, "Page $it says " + "x".repeat(2_000)) }
        val (sample, whole) = DocText.overview(long, "page", budget = 12_000)
        assertFalse(whole)
        assertTrue(sample.length <= 12_000)
        // Every page is represented, not just the first few.
        assertTrue(sample.contains("[page 1]") && sample.contains("[page 20]") && sample.contains("[page 40]"))
        assertTrue(sample.contains("Page 40 says"))
    }

    @Test
    fun tidyKeepsParagraphsButDropsNoise() {
        assertEquals("a b\nc\n\nd", DocText.tidy("a \t b\r\n c \n\n\n\nd  "))
    }
}
