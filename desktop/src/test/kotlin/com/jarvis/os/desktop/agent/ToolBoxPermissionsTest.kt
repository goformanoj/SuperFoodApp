package com.jarvis.os.desktop.agent

import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.knowledge.FileSearch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * The laptop-files permission (user request, 2026-09-29: "no access to any files on the
 * laptop right now"): OFF means search_files and open_file are gone from the model's tool
 * list AND refuse if called anyway (defence in depth, not just a hidden schema), and
 * read_document can no longer import a NEW file by path — but a document the user already
 * attached stays fully readable either way, because sharing it was the user's own action.
 */
class ToolBoxPermissionsTest {

    private val brain = Brain.inMemory { 1_000L }
    @After fun close() = brain.close()

    private val host = object : ToolBox.Host {
        override fun openUrl(url: String) = true
        override fun openApp(name: String): String? = null
        override fun clipboardText(): String? = null
        override fun openFile(path: String) = true
        override suspend fun searchFiles(q: FileSearch.Query) = listOf(FileSearch.Found("C:\\x\\found.pdf", null, null))
    }

    private fun tools(allowed: Boolean) = ToolBox(brain, host, { 1_000L }, ZoneId.of("Asia/Kolkata"), filesAllowed = { allowed })
    private fun run(t: ToolBox, name: String, args: String) = runBlocking { t.execute(name, args, sourceConversation = "c1") }

    @Test
    fun offHidesTheFileToolsFromTheModelButReadDocumentStays() {
        val names = tools(false).specs.map { it.name }
        assertFalse("search_files" in names)
        assertFalse("open_file" in names)
        assertTrue("read_document" in names)      // still needed for attached documents
        assertTrue("search_documents" in names)   // only searches what's already attached/known
        val on = tools(true).specs.map { it.name }
        assertTrue("search_files" in on && "open_file" in on)
    }

    @Test
    fun offRefusesSearchFilesAndOpenFileEvenIfCalledAnyway() {
        val t = tools(false)
        val search = run(t, "search_files", """{"query":"invoice"}""")
        assertFalse(search.ok)
        assertTrue(search.summary.contains("Permissions"))
        val open = run(t, "open_file", """{"path":"C:\\x\\found.pdf"}""")
        assertFalse(open.ok)
        assertTrue(open.summary.contains("Permissions"))
    }

    @Test
    fun onAllowsBoth() {
        val t = tools(true)
        assertTrue(run(t, "search_files", """{"query":"invoice"}""").ok)
        val f = File.createTempFile("jarvis-test", ".txt").apply { writeText("x"); deleteOnExit() }
        assertTrue(run(t, "open_file", "{\"path\":\"${f.path.replace("\\", "\\\\")}\"}").ok)
    }

    @Test
    fun offStillReadsADocumentTheUserAttachedThemselves() = runBlocking {
        val d = brain.addDocument("lease.txt", "txt", "part", listOf(1 to "Rent is due Friday."), listOf(1 to "Rent is due Friday."))
        brain.attachDocument("c1", d.id)
        val r = run(tools(false), "read_document", """{"document":"lease"}""")
        assertTrue(r.forModel, r.ok)
        assertTrue(org.json.JSONObject(r.forModel).getString("text").contains("Rent is due Friday"))
    }

    @Test
    fun offRefusesToImportANewFileByPathEvenIfTheModelKnowsOne() {
        val f = File.createTempFile("jarvis-test", ".txt").apply { writeText("secret laptop content"); deleteOnExit() }
        val r = run(tools(false), "read_document", "{\"document\":\"${f.path.replace("\\", "\\\\")}\"}")
        assertFalse(r.ok)
        assertEquals(0, brain.documents().size)   // never even read off disk
    }

    @Test
    fun onImportsANewFileByPathAsBefore() {
        val f = File.createTempFile("jarvis-test", ".txt").apply { writeText("hello from disk"); deleteOnExit() }
        val r = run(tools(true), "read_document", "{\"document\":\"${f.path.replace("\\", "\\\\")}\"}")
        assertTrue(r.forModel, r.ok)
        assertEquals(1, brain.documents().size)
    }

    // --- list_folder (live miss 2026-10-03: "access the SuperFoodApp folder" got a run of questions) ---

    @Test
    fun offHidesListFolderAndRefusesItWithTheWayToTurnItOn() {
        val t = tools(false)
        assertFalse("list_folder" in t.specs.map { it.name })
        val r = run(t, "list_folder", """{"folder":"SuperFoodApp"}""")
        assertFalse(r.ok)
        assertTrue(r.summary.contains("Settings → Permissions → Laptop files"))
    }

    @Test
    fun onListsAFolderGivenByFullPath() {
        val d = java.nio.file.Files.createTempDirectory("jarvis-proj").toFile().apply { deleteOnExit() }
        File(d, "app").mkdirs(); File(d, "README.md").writeText("hi"); File(d, ".env").writeText("KEY=1")
        val r = run(tools(true), "list_folder", "{\"folder\":\"${d.path.replace("\\", "\\\\")}\"}")
        assertTrue(r.forModel, r.ok)
        val j = org.json.JSONObject(r.forModel)
        assertEquals(2, j.getInt("total"))
        assertEquals("app", j.getJSONArray("entries").getJSONObject(0).getString("name"))
        assertTrue("the .env is counted but never shown", j.has("hidden") && !r.forModel.contains(".env\""))
    }

    @Test
    fun onFindsAFolderByNameUnderTheHostsRoots() {
        val root = java.nio.file.Files.createTempDirectory("jarvis-home").toFile().apply { deleteOnExit() }
        File(root, "Work/SuperFoodApp").mkdirs(); File(root, "Work/SuperFoodApp/notes.txt").writeText("x")
        val hostWithRoots = object : ToolBox.Host by host { override fun folderRoots() = listOf(root) }
        val t = ToolBox(brain, hostWithRoots, { 1_000L }, ZoneId.of("Asia/Kolkata"), filesAllowed = { true })
        val r = run(t, "list_folder", """{"folder":"super food app"}""")
        assertTrue(r.forModel, r.ok)
        assertTrue(r.summary.contains("SuperFoodApp"))
    }

    @Test
    fun anUnknownFolderNamedInWordsSaysSoAndAsksForThePathOnce() {
        val r = run(tools(true), "list_folder", """{"folder":"NoSuchFolderAnywhere"}""")
        assertFalse(r.ok)
        assertTrue(r.summary.contains("full path"))
    }
}
