package com.jarvis.os.files

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.jarvis.os.debug.DebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a requested artifact into a real file on disk.
 *
 * PDFs use Android's own [PdfDocument] — no library, no network, no cost, and it
 * works offline. Markdown-ish input is rendered plainly: headings bold and
 * larger, list items indented with a bullet. Deliberately simple, because a
 * half-supported Markdown renderer looks worse than clean plain text.
 */
object ArtifactWriter {

    fun write(context: Context, request: ArtifactRequest, store: ArtifactStore): Artifact? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = ArtifactActions.fileName(request.title, request.kind, stamp)
        val file = File(store.directory(), name)
        val ok = when (request.kind) {
            ArtifactKind.NOTE -> runCatching {
                file.writeText("# ${request.title}\n\n${request.body}\n")
            }.isSuccess
            ArtifactKind.PDF -> writePdf(file, request)
        }
        if (!ok) {
            DebugLog.log(DebugLog.Stage.FILE, "could not write ${request.kind.id} \"${request.title}\"")
            return null
        }
        val artifact = Artifact(
            fileName = name,
            title = request.title,
            kind = request.kind,
            createdMillis = System.currentTimeMillis(),
            sizeBytes = file.length(),
        )
        store.add(artifact)
        DebugLog.log(DebugLog.Stage.FILE, "made ${request.kind.id} \"${request.title}\" (${file.length()} bytes)")
        return artifact
    }

    private fun writePdf(file: File, request: ArtifactRequest): Boolean = runCatching {
        val doc = PdfDocument()
        val body = Paint().apply { textSize = BODY_SIZE; isAntiAlias = true }
        val heading = Paint().apply {
            textSize = HEADING_SIZE
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titlePaint = Paint().apply {
            textSize = TITLE_SIZE
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        var canvas = page.canvas
        var y = MARGIN + TITLE_SIZE
        var pageNo = 1

        canvas.drawText(request.title.take(60), MARGIN, y, titlePaint)
        y += TITLE_SIZE

        fun newPageIfNeeded(lineHeight: Float) {
            if (y + lineHeight <= PAGE_H - MARGIN) return
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = MARGIN + BODY_SIZE
        }

        request.body.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {
                    newPageIfNeeded(BODY_SIZE)
                    y += BODY_SIZE * 0.6f
                }
                line.startsWith("#") -> {
                    val text = line.trimStart('#', ' ')
                    newPageIfNeeded(HEADING_SIZE * 1.8f)
                    y += HEADING_SIZE * 0.6f
                    wrap(text, heading, PAGE_W - 2 * MARGIN).forEach {
                        newPageIfNeeded(HEADING_SIZE * 1.4f)
                        canvas.drawText(it, MARGIN, y, heading)
                        y += HEADING_SIZE * 1.4f
                    }
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val text = "•  " + line.trimStart().removeRange(0, 2).trim()
                    wrap(text, body, PAGE_W - 2 * MARGIN - INDENT).forEach {
                        newPageIfNeeded(BODY_SIZE * 1.5f)
                        canvas.drawText(it, MARGIN + INDENT, y, body)
                        y += BODY_SIZE * 1.5f
                    }
                }
                else -> wrap(line, body, PAGE_W - 2 * MARGIN).forEach {
                    newPageIfNeeded(BODY_SIZE * 1.5f)
                    canvas.drawText(it, MARGIN, y, body)
                    y += BODY_SIZE * 1.5f
                }
            }
        }

        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }.isSuccess

    /** Greedy word wrap — a PDF has a hard page width, unlike a screen. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = mutableListOf<String>()
        var line = StringBuilder()
        text.split(" ").forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) out.add(line.toString())
                line = StringBuilder(word)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    // A4 at 72dpi.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val INDENT = 16f
    private const val TITLE_SIZE = 20f
    private const val HEADING_SIZE = 14f
    private const val BODY_SIZE = 11f
}
