package com.jarvis.os.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Part F end-to-end on a real Android runtime (emulator): `ArtifactWriter` uses
 * `android.graphics.pdf.PdfDocument`, which is device-only, so this is the one place
 * we prove the file is actually produced — not just that the marker parsed. Asserts a
 * real PDF (with the %PDF magic header) and a markdown note land on disk and register
 * in the store.
 */
@RunWith(AndroidJUnit4::class)
class ArtifactWriterInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun writes_a_real_pdf_that_registers_and_starts_with_the_pdf_header() {
        val store = ArtifactStore(context)
        val request = ArtifactRequest(
            kind = ArtifactKind.PDF,
            title = "Meeting notes",
            body = "# Agenda\n- point one\n- point two\n\nSome closing remarks that wrap onto the page.",
        )
        val artifact = ArtifactWriter.write(context, request, store)
        assertNotNull("write() should return the created artifact", artifact)

        val file = store.fileFor(artifact!!)
        assertTrue("the PDF file exists and is non-empty", file.exists() && file.length() > 0)
        val header = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            .map { it.toInt().toChar() }.joinToString("")
        assertEquals("a real PDF starts with %PDF", "%PDF", header)
        assertTrue("the artifact is listed in the store", store.list().any { it.fileName == artifact.fileName })
        assertEquals("pdf", artifact.kind.extension)

        store.delete(artifact) // keep the emulator's files dir clean between runs
    }

    @Test
    fun writes_a_markdown_note_with_the_title_and_body() {
        val store = ArtifactStore(context)
        val request = ArtifactRequest(ArtifactKind.NOTE, "Ideas", "first idea\nsecond idea")
        val artifact = ArtifactWriter.write(context, request, store)
        assertNotNull(artifact)

        val text = store.fileFor(artifact!!).readText()
        assertTrue("the note leads with its title as an H1", text.startsWith("# Ideas"))
        assertTrue(text.contains("first idea") && text.contains("second idea"))
        assertEquals("md", artifact.kind.extension)

        store.delete(artifact)
    }
}
