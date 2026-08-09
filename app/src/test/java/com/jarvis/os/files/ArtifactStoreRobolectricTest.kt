package com.jarvis.os.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Files index persists as JSON in `filesDir/artifacts/index.json`, so the
 * round-trip, the newest-first ordering, the missing-file filtering, and the
 * corrupt-index fallback need Robolectric to exercise real `org.json` + `File` I/O.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtifactStoreRobolectricTest {

    private lateinit var context: Context
    private lateinit var store: ArtifactStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ArtifactStore(context)
    }

    private fun artifact(name: String, created: Long, kind: ArtifactKind = ArtifactKind.PDF) =
        Artifact(fileName = name, title = name.substringBefore('-'), kind = kind, createdMillis = created, sizeBytes = 7)

    /** The store lists only artifacts whose file is actually on disk. */
    private fun touch(a: Artifact) = store.fileFor(a).writeText("x")

    @Test
    fun `list returns present artifacts newest-first`() {
        val older = artifact("a-1.pdf", created = 1_000)
        val newer = artifact("b-2.md", created = 2_000, kind = ArtifactKind.NOTE)
        store.add(older); store.add(newer)
        touch(older); touch(newer)

        val list = store.list()
        assertEquals(2, list.size)
        assertEquals("newest first", "b-2.md", list[0].fileName)
        assertEquals("a-1.pdf", list[1].fileName)
        assertEquals(ArtifactKind.NOTE, list[0].kind)
    }

    @Test
    fun `an index entry whose file is gone is filtered out`() {
        val ghost = artifact("ghost-1.pdf", created = 1_000)
        store.add(ghost) // indexed, but no file written
        assertTrue("a missing file must not appear in the Files tab", store.list().isEmpty())
    }

    @Test
    fun `delete removes both the file and the index entry`() {
        val a = artifact("doc-1.pdf", created = 1_000)
        store.add(a); touch(a)
        assertTrue(store.fileFor(a).exists())

        store.delete(a)
        assertFalse("the file is gone", store.fileFor(a).exists())
        assertTrue("the index no longer lists it", store.list().none { it.fileName == a.fileName })
    }

    @Test
    fun `adding the same file name replaces the earlier entry`() {
        val first = artifact("dup-1.pdf", created = 1_000).copy(title = "First")
        val second = artifact("dup-1.pdf", created = 2_000).copy(title = "Second")
        store.add(first); store.add(second); touch(second)

        val list = store.list()
        assertEquals(1, list.size)
        assertEquals("Second", list[0].title)
    }

    @Test
    fun `a corrupt index falls back to empty instead of crashing the Files tab`() {
        File(store.directory(), "index.json").writeText("{ this is not valid json")
        assertTrue(ArtifactStore(context).list().isEmpty())
    }
}
