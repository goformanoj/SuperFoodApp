package com.jarvis.os.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactActionsTest {

    @Test
    fun `parses a pdf block`() {
        val (clean, files) = ArtifactActions.parse(
            """
            Here are the key points.
            <<FILE|pdf|Meeting notes>>
            # Decisions
            - ship on Friday
            <<ENDFILE>>
            """.trimIndent(),
        )

        assertEquals("Here are the key points.", clean)
        val file = files.single()
        assertEquals(ArtifactKind.PDF, file.kind)
        assertEquals("Meeting notes", file.title)
        assertTrue(file.body.contains("- ship on Friday"))
    }

    @Test
    fun `a multi-line body survives intact`() {
        val (_, files) = ArtifactActions.parse("<<FILE|note|Ideas>>\nline one\nline two\n\nline four\n<<ENDFILE>>")

        assertEquals("line one\nline two\n\nline four", files.single().body)
    }

    @Test
    fun `a forgotten end marker still produces the file`() {
        // The model drops closing markers often enough that losing the whole
        // document over it would be the wrong trade.
        val (_, files) = ArtifactActions.parse("Sure.\n<<FILE|pdf|Summary>>\nthe body")

        assertEquals(1, files.size)
        assertEquals("the body", files.single().body)
    }

    @Test
    fun `an empty body creates nothing`() {
        assertTrue(ArtifactActions.parse("<<FILE|pdf|Empty>>\n<<ENDFILE>>").second.isEmpty())
    }

    @Test
    fun `an unknown kind falls back to a note rather than failing`() {
        assertEquals(ArtifactKind.NOTE, ArtifactActions.parse("<<FILE|spreadsheet|X>>\nbody<<ENDFILE>>").second.single().kind)
    }

    @Test
    fun `a missing title gets a usable one`() {
        assertEquals("Untitled", ArtifactActions.parse("<<FILE|pdf|>>\nbody<<ENDFILE>>").second.single().title)
    }

    @Test
    fun `the block never reaches the spoken reply`() {
        val (clean, _) = ArtifactActions.parse("Done.\n<<FILE|pdf|X>>\nsecret body\n<<ENDFILE>>")

        assertFalse(clean.contains("<<"))
        assertFalse("the document body must not be read aloud", clean.contains("secret body"))
    }

    @Test
    fun `a runaway body is truncated`() {
        val huge = "x".repeat(ArtifactActions.MAX_BODY + 5_000)

        assertEquals(ArtifactActions.MAX_BODY, ArtifactActions.parse("<<FILE|note|Big>>\n$huge<<ENDFILE>>").second.single().body.length)
    }

    @Test
    fun `plain conversation makes no files`() {
        val chat = "I could put that in a PDF if you like."

        assertEquals(chat, ArtifactActions.parse(chat).first)
        assertTrue(ArtifactActions.parse(chat).second.isEmpty())
    }

    @Test
    fun `file names are safe and keep the extension`() {
        assertEquals("meeting-notes-20260728.pdf", ArtifactActions.fileName("Meeting Notes!", ArtifactKind.PDF, "20260728"))
        assertEquals("artifact-1.md", ArtifactActions.fileName("///", ArtifactKind.NOTE, "1"))
    }
}
