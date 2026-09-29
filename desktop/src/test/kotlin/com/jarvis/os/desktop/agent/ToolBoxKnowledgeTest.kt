package com.jarvis.os.desktop.agent

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.agent.ToolBox.Risk
import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.knowledge.FileSearch
import com.jarvis.os.desktop.knowledge.KnowledgeClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

/** Phase 5 (AGENT_PLAN §5): the knowledge tools: documents, files, the web, the screen. */
class ToolBoxKnowledgeTest {

    @get:Rule val tmp = TemporaryFolder()
    private val brain = Brain.inMemory { 1_000L }
    @After fun close() = brain.close()

    private val searched = mutableListOf<String>()
    private val fileQueries = mutableListOf<FileSearch.Query>()
    private val openedFiles = mutableListOf<String>()
    private var screenLooks = 0
    private val openedUrls = mutableListOf<String>()
    var youtubeVideoResult: com.jarvis.os.desktop.knowledge.YouTubeSearch.Result? = null
    var youtubePlaylistResult: com.jarvis.os.desktop.knowledge.YouTubeSearch.Result? = null
    private val host = object : ToolBox.Host {
        override fun openUrl(url: String): Boolean { openedUrls += url; return true }
        override fun openApp(name: String): String? = null
        override fun clipboardText(): String? = null
        override fun openFile(path: String): Boolean { openedFiles += path; return true }
        override suspend fun webSearch(query: String): KnowledgeClient.WebAnswer {
            searched += query
            return KnowledgeClient.WebAnswer("The RBI held rates at 5.5%.", listOf(KnowledgeClient.Source("RBI", "https://www.rbi.org.in/x")))
        }
        override suspend fun searchFiles(q: FileSearch.Query): List<FileSearch.Found> {
            fileQueries += q
            return listOf(FileSearch.Found("C:\\Users\\me\\Documents\\Invoice-March.pdf", "2026-03-14T10:22", 48_213))
        }
        override suspend fun askAboutScreen(question: String): String { screenLooks++; return "A “disk full” error from OneDrive." }
        override suspend fun youtubeVideo(query: String) = youtubeVideoResult
        override suspend fun youtubePlaylist(query: String) = youtubePlaylistResult
    }
    private val tools = ToolBox(brain, host, { 1_000L }, ZoneId.of("Asia/Kolkata"))
    private val conv = brain.createConversation("Lease").id

    private fun run(name: String, args: String) = runBlocking { tools.execute(name, args, sourceConversation = conv) }
    private fun json(r: ToolBox.Result) = JSONObject(r.forModel)

    private fun leaseFile() = tmp.newFile("lease.txt").apply {
        writeText("Rent is due on the fifth of each month.\n\nEither party may end the lease with two months notice.")
    }

    // ── risk ──

    @Test
    fun onlyTheScreenLookSharesAndEveryKnowledgeToolIsDeclared() {
        val names = tools.specs.map { it.name }
        listOf("web_search", "read_document", "search_documents", "search_files", "open_file", "look_at_screen").forEach { assertTrue(it, it in names) }
        assertEquals(setOf("look_at_screen"), tools.specs.filter { it.risk == Risk.SHARES }.map { it.name }.toSet())
        assertEquals(setOf("delete_task", "forget", "look_at_screen"), tools.specs.filter { it.risk.needsApproval }.map { it.name }.toSet())
        assertTrue(tools.specs.size <= 24)     // the Worker's MAX_TOOLS
        assertTrue(tools.approvalNote("look_at_screen").contains("isn't saved"))
        assertEquals("This can't be undone.", tools.approvalNote("delete_task"))
    }

    // ── S7: the web ──

    @Test
    fun webSearchReturnsTheAnswerWithSources() {
        val r = run("web_search", """{"query":"latest RBI rate decision"}""")
        assertTrue(r.ok)
        assertEquals(listOf("latest RBI rate decision"), searched)
        assertEquals("The RBI held rates at 5.5%.", json(r).getString("answer"))
        assertEquals("https://www.rbi.org.in/x", json(r).getJSONArray("sources").getJSONObject(0).getString("url"))
        assertEquals("Searched the web for “latest RBI rate decision” · rbi.org.in", r.summary)
        assertFalse(run("web_search", """{"query":"  "}""").ok)
    }

    // ── S4: documents ──

    @Test
    fun aPathIsReadInKeptAndAttachedToTheChat() {
        val f = leaseFile()
        val r = run("read_document", JSONObject().put("document", f.absolutePath).toString())
        assertTrue(r.forModel, r.ok)
        val out = json(r)
        assertEquals("lease.txt", out.getString("document"))
        assertTrue(out.getBoolean("complete"))
        assertTrue(out.getString("text").startsWith("[part 1]\nRent is due"))
        assertEquals(listOf("lease.txt"), brain.attachedDocuments(conv).map { it.name })
        // Reading it again reuses the stored copy.
        run("read_document", JSONObject().put("document", f.absolutePath).toString())
        assertEquals(1, brain.documents().size)
    }

    @Test
    fun thisDocumentMeansTheOneAttachedHere() {
        val f = leaseFile()
        runBlocking { com.jarvis.os.desktop.knowledge.Library.import(brain, f, conv) }
        assertTrue(run("read_document", """{"document":"this document"}""").ok)
        assertTrue(run("read_document", """{"document":"lease"}""").ok)
        assertTrue(run("read_document", """{"document":""}""").ok)
        val miss = run("read_document", """{"document":"passport scan"}""")
        assertFalse(miss.ok)
        assertTrue(miss.forModel.contains("attach"))
    }

    @Test
    fun aPageRangeReadsJustThosePages() {
        val d = brain.addDocument("report.pdf", "pdf", "page", (1..5).map { it to "Page $it text" }, emptyList())
        brain.attachDocument(conv, d.id)
        val out = json(run("read_document", """{"document":"report","pages":"2-3"}"""))
        assertEquals("[page 2]\nPage 2 text\n\n[page 3]\nPage 3 text", out.getString("text"))
        assertEquals(tools.pageRange("9", 5), 5..5)
        assertEquals(tools.pageRange("3 to 4", 5), 3..4)
        assertNull(tools.pageRange("all", 5))
    }

    @Test
    fun documentAnswersCiteTheirPage() {
        runBlocking { com.jarvis.os.desktop.knowledge.Library.import(brain, leaseFile(), conv) }
        val r = run("search_documents", """{"query":"notice period"}""")
        val p = json(r).getJSONArray("passages").getJSONObject(0)
        assertEquals("lease.txt", p.getString("document"))
        assertEquals("part 1", p.getString("where"))
        assertTrue(p.getString("text").contains("two months notice"))
        assertTrue(json(run("search_documents", """{"query":"zebra"}""")).has("note"))
        assertFalse(run("search_documents", """{"query":"x","document":"nothing like it"}""").ok)
    }

    @Test
    fun actionPointsGoInAsOneBatchWithOneUndo() {
        val r = run("add_tasks", """{"tasks":[{"title":"Send the deck to Priya","due":"2026-10-02"},{"title":"Book the design review","due":"2026-09-29T11:00"},{"title":"Draft the invite"}]}""")
        assertTrue(r.forModel, r.ok)
        assertEquals(3, json(r).getInt("count"))
        assertEquals(3, brain.openTasks().size)
        assertTrue(brain.openTasks().all { it.sourceConversation == conv })
        assertEquals("Removed 3 tasks", tools.undo(r.undo!!))
        assertTrue(brain.openTasks().isEmpty())
    }

    @Test
    fun aBadItemAddsNothingRatherThanHalfAList() {
        val r = run("add_tasks", """{"tasks":[{"title":"Fine"},{"title":"Bad date","due":"someday"}]}""")
        assertFalse(r.ok)
        assertTrue(r.summary.contains("Bad date"))
        assertTrue(brain.openTasks().isEmpty())
        assertFalse(run("add_tasks", """{"tasks":[]}""").ok)
        assertFalse(run("add_tasks", """{"tasks":[{"notes":"no title"}]}""").ok)
        val many = (1..26).joinToString(",") { """{"title":"t$it"}""" }
        assertFalse(run("add_tasks", """{"tasks":[$many]}""").ok)
    }

    // ── S5: files on the laptop ──

    @Test
    fun fileSearchGoesThroughTheSanitisedQuery() {
        val r = run("search_files", """{"query":"invoice","kind":"document","modified_after":"2026-03-01","modified_before":"2026-03-31"}""")
        assertTrue(r.ok)
        val q = fileQueries.single()
        assertEquals(listOf("invoice"), q.words)
        assertEquals("document", q.kind)
        assertEquals("Invoice-March.pdf", json(r).getJSONArray("files").getJSONObject(0).getString("name"))
        assertFalse(run("search_files", """{"query":"?!"}""").ok)
    }

    @Test
    fun openFileOpensDocumentsButNeverPrograms() {
        val doc = tmp.newFile("notes.pdf")
        assertTrue(run("open_file", JSONObject().put("path", doc.absolutePath).toString()).ok)
        assertEquals(listOf(doc.path), openedFiles)
        for (name in listOf("setup.exe", "run.bat", "x.ps1", "evil.lnk", "a.js", "b.vbs", "c.msi", "d.msc", "e.scf", "f.chm", "g.wsc")) {
            val f = tmp.newFile(name)
            val r = run("open_file", JSONObject().put("path", f.absolutePath).toString())
            assertFalse(name, r.ok)
        }
        assertEquals(1, openedFiles.size)
        assertFalse(run("open_file", """{"path":"C:\\nope\\missing.pdf"}""").ok)
    }

    // ── S6: the screen, only with the user's OK ──

    @Test
    fun theScreenIsOnlyLookedAtAfterApproval() = runBlocking {
        val asks = mutableListOf<AgentLoop.Ask>()
        val script = ArrayDeque(listOf(
            AgentClient.Reply("", listOf(AgentClient.ToolCall("s", "look_at_screen", """{"question":"What's this error?"}"""))),
            AgentClient.Reply("Okay.", emptyList()),
        ))
        AgentLoop(tools, { _, _, _ -> script.removeFirst() }, approve = { asks += it; false }, onStep = { _, _ -> })
            .run(listOf(ChatTurn(ChatTurn.USER, "what's this error")), "", conv)
        assertEquals(0, screenLooks)                       // declined → never captured
        assertEquals("Look at your screen to answer: “What's this error?”", asks.single().description)
        assertTrue(asks.single().note.contains("screenshot"))

        val again = ArrayDeque(listOf(
            AgentClient.Reply("", listOf(AgentClient.ToolCall("s", "look_at_screen", """{"question":"What's this error?"}"""))),
            AgentClient.Reply("It's OneDrive saying the disk is full.", emptyList()),
        ))
        val answer = AgentLoop(tools, { _, _, _ -> again.removeFirst() }, approve = { true }, onStep = { _, _ -> })
            .run(listOf(ChatTurn(ChatTurn.USER, "what's this error")), "", conv)
        assertEquals(1, screenLooks)
        assertEquals("It's OneDrive saying the disk is full.", answer)
    }

    @Test
    fun aHostWithoutKnowledgeSaysSoInsteadOfCrashing() {
        val bare = ToolBox(brain, object : ToolBox.Host {
            override fun openUrl(url: String) = true
            override fun openApp(name: String): String? = null
            override fun clipboardText(): String? = null
        })
        val r = runBlocking { bare.execute("web_search", """{"query":"x"}""", null) }
        assertFalse(r.ok)
        assertTrue(r.summary.contains("isn't available"))
    }

    // ── play_youtube (found live, 2026-09-29: the model guessed a video URL that didn't
    // exist, then opened a page that didn't start playing) ──

    @Test
    fun playsARealVideoDirectlyNeverGuessingAUrl() {
        youtubeVideoResult = com.jarvis.os.desktop.knowledge.YouTubeSearch.Result("bzSTpdcs-EI", "Channa Mereya - Arijit Singh")
        val r = run("play_youtube", """{"query":"Channa Mereya Arijit Singh"}""")
        assertTrue(r.forModel, r.ok)
        assertEquals("https://www.youtube.com/watch?v=bzSTpdcs-EI&autoplay=1", openedUrls.single())
        assertEquals("Playing “Channa Mereya - Arijit Singh” on YouTube", r.summary)
    }

    @Test
    fun playsAPlaylistStartingItRatherThanJustOpeningTheListing() {
        youtubePlaylistResult = com.jarvis.os.desktop.knowledge.YouTubeSearch.Result("PL123", "Best Of Arijit Singh", startVideoId = "ElZfdU54Cp8")
        val r = run("play_youtube", """{"query":"best arjit singh playlist","type":"playlist"}""")
        assertTrue(r.ok)
        assertEquals("https://www.youtube.com/watch?v=ElZfdU54Cp8&list=PL123&autoplay=1", openedUrls.single())
    }

    @Test
    fun noResultIsReportedHonestlyRatherThanFallingBackToAGuess() {
        youtubeVideoResult = null
        val r = run("play_youtube", """{"query":"asdkfjasldkfj nonsense"}""")
        assertFalse(r.ok)
        assertTrue(openedUrls.isEmpty())
    }

    @Test
    fun openUrlAddsAutoplayForAYouTubeLinkButLeavesEverythingElseAlone() {
        run("open_url", """{"url":"https://www.youtube.com/watch?v=abc12345678"}""")
        assertEquals("https://www.youtube.com/watch?v=abc12345678&autoplay=1", openedUrls.single())
        openedUrls.clear()
        run("open_url", """{"url":"https://example.com/page?x=1"}""")
        assertEquals("https://example.com/page?x=1", openedUrls.single())     // untouched
        openedUrls.clear()
        run("open_url", """{"url":"https://youtu.be/abc12345678?autoplay=0"}""")
        assertEquals("https://youtu.be/abc12345678?autoplay=0", openedUrls.single())   // already has one — not doubled
    }
}
