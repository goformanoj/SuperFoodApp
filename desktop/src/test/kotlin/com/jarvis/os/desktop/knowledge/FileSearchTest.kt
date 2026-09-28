package com.jarvis.os.desktop.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FileSearchTest {

    @Test
    fun theInvoiceFromMarch_S5() {
        val q = FileSearch.query("invoice", kind = "document", after = "2026-03-01", before = "2026-03-31")!!
        assertEquals(listOf("invoice"), q.words)
        val sql = FileSearch.sql(q)
        assertTrue(sql, sql.contains("CONTAINS(*,'\"invoice*\"')"))
        assertTrue(sql.contains("System.FileName LIKE '%invoice%'"))
        assertTrue(sql.contains("System.Kind = 'document'"))
        assertTrue(sql.contains("System.DateModified >= '2026-03-01 00:00:00'"))
        // "before 31 March" includes the whole of the 31st.
        assertTrue(sql.contains("System.DateModified < '2026-04-01 00:00:00'"))
        assertTrue(sql.startsWith("SELECT TOP 25 System.ItemPathDisplay"))
        // Code files that merely mention invoices are left out…
        assertTrue(sql.contains("System.FileExtension <> '.js'"))
        assertTrue(sql.endsWith("ORDER BY System.DateModified DESC"))
    }

    @Test
    fun nothingTheModelSaysCanChangeTheQueryShape() {
        val q = FileSearch.query("invoice' OR 1=1; DROP --\"quote\" (march)")!!
        val sql = FileSearch.sql(q)
        // Only letters/digits/._- survive: no quotes, semicolons, parentheses or comment dashes from input.
        q.words.forEach { w -> assertTrue(w, w.matches(Regex("[\\p{L}\\p{N}._-]+"))) }
        assertFalse(sql.contains("1=1"))
        assertFalse(sql.contains(";"))
        assertEquals(sql.count { it == '\'' } % 2, 0)
        // A bogus kind or date is dropped, not passed through.
        val odd = FileSearch.query("report", kind = "'; drop", after = "yesterday")!!
        assertNull(odd.kind)
        assertNull(odd.after)
    }

    @Test
    fun aFileTypeInTheRequestBecomesAnExtensionFilter() {
        val q = FileSearch.query("tax pdf from 2025")!!
        assertEquals("pdf", q.extension)
        assertEquals(listOf("tax", "2025"), q.words)
        assertTrue(FileSearch.sql(q).contains("System.FileExtension = '.pdf'"))
        // …unless a type was asked for.
        assertFalse(FileSearch.sql(q).contains("<> '.js'"))
        // Just a type is still a search ("my PDFs").
        assertEquals("xlsx", FileSearch.query(".xlsx")!!.extension)
    }

    @Test
    fun nothingToSearchForIsNull() {
        assertNull(FileSearch.query("  the file  "))
        assertNull(FileSearch.query("?!"))
    }

    @Test
    fun wordsAreCappedAndDeduplicated() {
        val q = FileSearch.query("a1 b2 c3 d4 e5 f6 g7 h8 a1")!!
        assertEquals(6, q.words.size)
        assertEquals(LocalDate.parse("2026-01-02"), FileSearch.query("x1", after = "2026-01-02T10:00")!!.after)
    }

    @Test
    fun resultsAreParsedAndAppInternalPlacesDropped() {
        val out = """
            C:\Users\me\Documents\Invoice-March.pdf	2026-03-14T10:22	48213
            C:\Users\me\AppData\Local\Temp\invoice.tmp	2026-03-14T10:22	1
            C:\Users\me\Documents\Invoice-March.pdf	2026-03-14T10:22	48213
            \\nas\share\invoice.docx
            garbage line
        """.trimIndent()
        val found = FileSearch.parse(out)
        assertEquals(2, found.size)
        assertEquals("Invoice-March.pdf", found[0].name)
        assertEquals("2026-03-14T10:22", found[0].modified)
        assertEquals(48213L, found[0].size)
        assertEquals("invoice.docx", found[1].name)
        assertNull(found[1].modified)
    }
}
