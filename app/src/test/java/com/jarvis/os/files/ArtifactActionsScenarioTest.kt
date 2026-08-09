package com.jarvis.os.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extra Part F marker-parsing scenarios beyond `ArtifactActionsTest`: several files
 * in one reply, case-insensitive kinds, and the title/body clamps.
 */
class ArtifactActionsScenarioTest {

    @Test
    fun `two file blocks in one reply produce two artifacts`() {
        val (_, files) = ArtifactActions.parse(
            "Sure. <<FILE|pdf|Notes>>\n# A\n<<ENDFILE>> and <<FILE|note|Ideas>>\nthing\n<<ENDFILE>>",
        )
        assertEquals(2, files.size)
        assertEquals(ArtifactKind.PDF, files[0].kind)
        assertEquals("Notes", files[0].title)
        assertEquals("# A", files[0].body)
        assertEquals(ArtifactKind.NOTE, files[1].kind)
        assertEquals("Ideas", files[1].title)
        assertEquals("thing", files[1].body)
    }

    @Test
    fun `the kind id is case-insensitive`() {
        val (_, files) = ArtifactActions.parse("<<FILE|PDF|X>>\nbody\n<<ENDFILE>>")
        assertEquals(ArtifactKind.PDF, files.single().kind)
    }

    @Test
    fun `an unknown kind falls back to a note`() {
        assertEquals(ArtifactKind.NOTE, ArtifactKind.fromId("spreadsheet"))
        assertEquals(ArtifactKind.PDF, ArtifactKind.fromId("pdf"))
    }

    @Test
    fun `a very long title is clamped to the max`() {
        val long = "T".repeat(100)
        val (_, files) = ArtifactActions.parse("<<FILE|note|$long>>\nbody\n<<ENDFILE>>")
        assertEquals(ArtifactActions.MAX_TITLE, files.single().title.length)
    }

    @Test
    fun `a runaway body is truncated to the max`() {
        val huge = "x".repeat(ArtifactActions.MAX_BODY + 500)
        val (_, files) = ArtifactActions.parse("<<FILE|note|Big>>\n$huge\n<<ENDFILE>>")
        assertEquals(ArtifactActions.MAX_BODY, files.single().body.length)
    }

    @Test
    fun `the file name is filesystem-safe and stamped`() {
        val name = ArtifactActions.fileName("My Report: Q3 (final)", ArtifactKind.PDF, "20260809-101112")
        assertTrue(name.endsWith("-20260809-101112.pdf"))
        assertFalse("no spaces", name.contains(" "))
        assertFalse("no colon", name.contains(":"))
    }
}
