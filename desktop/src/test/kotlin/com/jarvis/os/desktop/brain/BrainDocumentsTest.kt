package com.jarvis.os.desktop.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrainDocumentsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val brain = Brain.inMemory { 1_000L }
    @After fun close() = brain.close()

    private fun lease(path: String? = "C:\\docs\\lease.pdf", modified: Long? = 42L) = brain.addDocument(
        "lease.pdf", "pdf", "page",
        pages = listOf(1 to "Rent is due on the fifth of each month.", 2 to "Either party may end the lease with two months notice."),
        chunks = listOf(1 to "Rent is due on the fifth of each month.", 2 to "Either party may end the lease with two months notice."),
        path = path, modified = modified, sourceConversation = "c1",
    )

    @Test
    fun aQuestionFindsThePassageAndItsPage() {
        lease()
        val hits = brain.searchDocuments("what is the notice period to end the lease?")
        assertEquals(2, hits.first().page)
        assertEquals("lease.pdf", hits.first().docName)
        assertEquals("page", hits.first().unit)
        assertTrue(hits.first().snippet.contains("two months notice"))
    }

    @Test
    fun searchCanBeLimitedToOneDocument() {
        val a = lease()
        brain.addDocument("other.txt", "txt", "part", listOf(1 to "The notice board is in the hall."), listOf(1 to "The notice board is in the hall."))
        assertEquals(2, brain.searchDocuments("notice").size)
        assertEquals(listOf(a.id), brain.searchDocuments("notice", docId = a.id).map { it.docId })
        assertTrue(brain.searchDocuments("the of what").isEmpty())      // only stop words
    }

    @Test
    fun pagesComeBackInOrderAndByRange() {
        val d = lease()
        assertEquals(listOf(1, 2), brain.documentPages(d.id).map { it.first })
        assertEquals(listOf(2), brain.documentPages(d.id, 2, 2).map { it.first })
        assertEquals(2, d.pages)
        assertEquals(brain.documentPages(d.id).sumOf { it.second.length }, d.chars)
    }

    @Test
    fun anUnchangedFileIsRecognisedAChangedOneIsNot() {
        val d = lease()
        assertEquals(d.id, brain.documentFor("C:\\docs\\lease.pdf", 42L)?.id)
        assertNull(brain.documentFor("C:\\docs\\lease.pdf", 43L))
    }

    @Test
    fun attachmentsBelongToAChatAndNamesAreFindable() {
        val d = lease()
        val c = brain.createConversation("Lease questions")
        brain.attachDocument(c.id, d.id)
        brain.attachDocument(c.id, d.id)                 // idempotent
        assertEquals(listOf(d.id), brain.attachedDocuments(c.id).map { it.id })
        assertEquals(d.id, brain.findDocuments("LEASE").single().id)
        // The one search box finds documents by name too.
        assertTrue(brain.search("lease").any { it.kind == "document" && it.refId == d.id })
    }

    @Test
    fun removingADocumentRemovesEveryTrace() {
        val d = lease()
        val c = brain.createConversation("x")
        brain.attachDocument(c.id, d.id)
        brain.deleteDocument(d.id)
        assertNull(brain.document(d.id))
        assertTrue(brain.documentPages(d.id).isEmpty())
        assertTrue(brain.searchDocuments("notice").isEmpty())
        assertTrue(brain.attachedDocuments(c.id).isEmpty())
        assertTrue(brain.search("lease").none { it.kind == "document" })
    }

    @Test
    fun deletingAChatForgetsItsAttachmentsButKeepsTheDocument() {
        val d = lease()
        val c = brain.createConversation("x")
        brain.attachDocument(c.id, d.id)
        brain.deleteConversation(c.id)
        assertNotNull(brain.document(d.id))
        assertTrue(brain.attachedDocuments(c.id).isEmpty())
    }

    @Test
    fun aVersion1BrainOnDiskUpgradesInPlaceKeepingItsData() {
        val file = tmp.newFile("brain.db").also { it.delete() }
        Brain.open(file).use { b -> b.addTask("Keep me") }
        // Simulate a laptop that last ran the Phase 4 build: schema 1, no document tables.
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                listOf("conversation_docs", "doc_pages", "documents", "routines", "sync_outbox").forEach { st.execute("DROP TABLE $it") }
                st.execute("DROP TABLE doc_fts")
                st.execute("UPDATE meta SET value='1' WHERE key='schema'")
            }
        }
        Brain.open(file).use { b ->
            assertEquals("Keep me", b.openTasks().single().title)
            val d = b.addDocument("a.txt", "txt", "part", listOf(1 to "hello"), listOf(1 to "hello"))
            assertEquals(d.id, b.searchDocuments("hello").single().docId)
        }
    }

    @Test
    fun documentQueriesUseAnyWordButTheSearchBoxNeedsAll() {
        assertEquals("\"notice\"* OR \"period\"*", Brain.ftsQuery("What is the notice period?", all = false))
        assertEquals("\"what\"* \"is\"* \"the\"* \"notice\"* \"period\"*", Brain.ftsQuery("What is the notice period?"))
    }
}
