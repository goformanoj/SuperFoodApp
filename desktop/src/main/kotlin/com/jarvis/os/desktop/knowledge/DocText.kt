package com.jarvis.os.desktop.knowledge

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * Text out of the user's documents, locally (AGENT_PLAN §5, S4). Nothing is uploaded:
 * the text is extracted here, chunked and indexed in the brain, and only the parts a
 * question needs go to the model — which is also what keeps a long PDF inside the
 * daily allowance.
 *
 * PDF pages are real pages (citations say "page 4"). Word and text files have no pages,
 * so they are cut into ~3,000-character "parts" at paragraph breaks, and cited that way,
 * never with invented page numbers.
 */
object DocText {

    data class Page(val number: Int, val text: String)
    /** [unit] is what a citation calls a [Page]: "page" (PDF) or "part" (everything else). */
    data class Extracted(val name: String, val kind: String, val unit: String, val pages: List<Page>) {
        val chars: Int get() = pages.sumOf { it.text.length }
    }
    data class Chunk(val page: Int, val seq: Int, val text: String)

    val TEXT_KINDS = setOf("txt", "md", "markdown", "csv", "log", "json", "xml", "html", "htm", "rtf", "ini", "yaml", "yml")
    val KINDS = setOf("pdf", "docx") + TEXT_KINDS

    const val MAX_BYTES = 60L * 1024 * 1024
    const val MAX_PAGES = 800
    const val MAX_CHARS = 3_000_000
    const val PART_CHARS = 3_000
    private const val MIN_SAMPLE = 200
    /** "[page 12]\n" + " …" + "\n\n" around each sampled page. */
    private const val SAMPLE_OVERHEAD = 24

    /** Extracts [file]. Throws [IllegalArgumentException] with a sentence fit to show the user. */
    fun extract(file: File): Extracted {
        require(file.isFile) { "I can't find the file “${file.name}”." }
        require(file.length() <= MAX_BYTES) { "“${file.name}” is too big for me to read (over ${MAX_BYTES / 1024 / 1024} MB)." }
        val kind = file.extension.lowercase()
        val ex = when (kind) {
            "pdf" -> pdf(file)
            "docx" -> Extracted(file.name, kind, "part", parts(ZipFile(file).use { z ->
                val entry = z.getEntry("word/document.xml") ?: throw IllegalArgumentException("“${file.name}” doesn't look like a Word document.")
                z.getInputStream(entry).use { docxText(it) }
            }))
            "html", "htm" -> Extracted(file.name, kind, "part", parts(stripTags(readText(file))))
            in TEXT_KINDS -> Extracted(file.name, kind, "part", parts(readText(file)))
            "doc" -> throw IllegalArgumentException("“${file.name}” is an old Word (.doc) file — save it as .docx or PDF and give it to me again.")
            else -> throw IllegalArgumentException("I can read PDF, Word (.docx) and text files — not .$kind yet.")
        }
        require(ex.pages.any { it.text.isNotBlank() }) {
            if (kind == "pdf") "“${file.name}” has no text I can read — it's probably a scan. Reading scans isn't supported yet."
            else "“${file.name}” is empty."
        }
        return ex
    }

    private fun pdf(file: File): Extracted = try {
        Loader.loadPDF(file).use { doc ->
            val n = doc.numberOfPages
            require(n <= MAX_PAGES) { "“${file.name}” has $n pages — I read up to $MAX_PAGES." }
            val stripper = PDFTextStripper()
            var total = 0
            val pages = (1..n).map { p ->
                stripper.startPage = p
                stripper.endPage = p
                val t = if (total > MAX_CHARS) "" else tidy(stripper.getText(doc))
                total += t.length
                Page(p, t)
            }
            Extracted(file.name, "pdf", "page", pages)
        }
    } catch (e: InvalidPasswordException) {
        throw IllegalArgumentException("“${file.name}” is password-protected, so I can't read it.")
    }

    private fun readText(file: File): String {
        val bytes = file.readBytes()
        // UTF-8 unless it plainly isn't (Windows Notepad files are often ANSI).
        val utf8 = String(bytes, Charsets.UTF_8)
        val text = if (utf8.contains('�')) String(bytes, charset("windows-1252")) else utf8
        return text.removePrefix("﻿").take(MAX_CHARS)
    }

    /**
     * The visible text of a .docx body (word/document.xml): runs joined, one line per
     * paragraph, tabs and breaks kept. Pure — tested with a hand-built document.
     */
    fun docxText(xml: InputStream): String {
        val f = XMLInputFactory.newInstance().apply {
            // A document is data: never resolve external entities or DTDs (XXE).
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val r = f.createXMLStreamReader(xml)
        val out = StringBuilder()
        var inText = false
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                    "t" -> inText = true
                    "tab" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                }
                XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                    "t" -> inText = false
                    "p" -> out.append('\n')
                }
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (inText) out.append(r.text)
            }
            if (out.length > MAX_CHARS) break
        }
        r.close()
        return out.toString()
    }

    /** Cuts page-less text into ~[size]-character parts at paragraph (then line) breaks. */
    fun parts(text: String, size: Int = PART_CHARS): List<Page> {
        val clean = tidy(text)
        if (clean.isBlank()) return listOf(Page(1, ""))
        val out = mutableListOf<Page>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + size, clean.length)
            if (end < clean.length) {
                val para = clean.lastIndexOf("\n\n", end)
                val line = clean.lastIndexOf('\n', end)
                end = when {
                    para > start + size / 2 -> para
                    line > start + size / 2 -> line
                    else -> end
                }
            }
            out += Page(out.size + 1, clean.substring(start, end).trim())
            start = end
        }
        return out.filter { it.text.isNotEmpty() }.mapIndexed { i, p -> p.copy(number = i + 1) }
    }

    /**
     * Search chunks: each page cut into overlapping windows, so a sentence that straddles
     * a cut is still found whole. Chunks never cross pages — every hit has one citation.
     */
    fun chunks(pages: List<Page>, size: Int = 1_200, overlap: Int = 200): List<Chunk> {
        val out = mutableListOf<Chunk>()
        for (p in pages) {
            val t = p.text
            if (t.isBlank()) continue
            var start = 0
            var seq = 0
            while (start < t.length) {
                var end = minOf(start + size, t.length)
                if (end < t.length) {
                    val space = t.lastIndexOf(' ', end)
                    if (space > start + size / 2) end = space
                }
                out += Chunk(p.number, seq++, t.substring(start, end).trim())
                if (end >= t.length) break
                start = maxOf(end - overlap, start + 1)
            }
        }
        return out
    }

    /**
     * What the model gets when asked to read a whole document, within [budget] characters.
     * Short documents come whole. Long ones come as an even sample: the start of every
     * page (so a summary covers the whole thing, not just the first pages), plus a note
     * that `search_documents` finds specific details.
     */
    fun overview(pages: List<Page>, unit: String, budget: Int = 12_000): Pair<String, Boolean> {
        val real = pages.filter { it.text.isNotBlank() }
        val total = real.sumOf { it.text.length + 12 }
        if (total <= budget) return real.joinToString("\n\n") { "[$unit ${it.number}]\n${it.text}" } to true
        // At least ~200 characters per page shown; with more pages than that allows, every
        // k-th page evenly across the document (never just the first ones).
        val fits = maxOf(1, budget / (MIN_SAMPLE + SAMPLE_OVERHEAD))
        val shown = if (real.size <= fits) real else List(fits) { i -> real[i * real.size / fits] }
        val per = maxOf(MIN_SAMPLE, budget / shown.size - SAMPLE_OVERHEAD)
        val sb = StringBuilder()
        for (p in shown) {
            if (sb.length + 40 > budget) break
            val room = minOf(per, budget - sb.length - 20)
            val t = p.text.take(room)
            sb.append("[$unit ${p.number}]\n").append(t).append(if (t.length < p.text.length) " …" else "").append("\n\n")
        }
        return sb.toString().trimEnd() to false
    }

    /** A saved web page's words: scripts and styles dropped, tags removed, common entities decoded. */
    fun stripTags(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    /** Collapses PDF whitespace noise without losing paragraph breaks. */
    fun tidy(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
