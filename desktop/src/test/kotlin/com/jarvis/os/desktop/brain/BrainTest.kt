package com.jarvis.os.desktop.brain

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.ChatStore
import com.jarvis.os.desktop.brain.Brain.MemoryKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrainTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val brain = Brain.inMemory { now }
    @After fun close() = brain.close()

    // ── conversations ──

    @Test
    fun messagesKeepOrderAndBumpTheConversation() {
        val c = brain.createConversation("Budget")
        now += 10; brain.addMessage(c.id, ChatTurn.USER, "What's left this month?")
        now += 10; brain.addMessage(c.id, ChatTurn.ASSISTANT, "About 12k.")
        assertEquals(listOf("What's left this month?", "About 12k."), brain.messages(c.id).map { it.content })
        assertEquals(now, brain.conversation(c.id)!!.updated)
    }

    @Test
    fun pinnedFirstThenMostRecentAndArchivedHidden() {
        val a = brain.createConversation("A"); now += 1
        val b = brain.createConversation("B"); now += 1
        val c = brain.createConversation("C")
        brain.setPinned(a.id, true)
        brain.setArchived(c.id, true)
        assertEquals(listOf("A", "B"), brain.conversations().map { it.title })
        assertEquals(3, brain.conversations(includeArchived = true).size)
    }

    @Test
    fun deletingAConversationRemovesItsMessagesFromSearch() {
        val c = brain.createConversation("Holiday")
        brain.addMessage(c.id, ChatTurn.USER, "Book flights to Goa")
        assertTrue(brain.search("goa").isNotEmpty())
        brain.deleteConversation(c.id)
        assertTrue(brain.search("goa").isEmpty())
        assertTrue(brain.messages(c.id).isEmpty())
    }

    // ── projects ──

    @Test
    fun deletingAProjectKeepsItsChatsUngrouped() {
        val p = brain.createProject("Work")
        val c = brain.createConversation("Standup", projectId = p.id)
        brain.deleteProject(p.id)
        assertNull(brain.conversation(c.id)!!.projectId)
        assertTrue(brain.projects().isEmpty())
    }

    // ── memory ──

    @Test
    fun rememberIsTypedDedupedAndSourced() {
        val m = brain.remember("Sister is Asha", MemoryKind.PERSON, sourceConversation = "c1")!!
        assertEquals(MemoryKind.PERSON, m.kind)
        assertEquals("c1", m.sourceConversation)
        assertNull(brain.remember("sister is ASHA"))
        assertEquals(1, brain.memories().size)
        assertEquals(1, brain.memories(MemoryKind.PERSON).size)
        assertTrue(brain.memories(MemoryKind.PLACE).isEmpty())
    }

    @Test
    fun forgetRemovesEverythingMentioningIt() {
        brain.remember("Sister is Asha"); brain.remember("Asha likes cats"); brain.remember("Works at Acme")
        assertEquals(2, brain.forget("asha"))
        assertEquals(listOf("Works at Acme"), brain.memories().map { it.text })
        assertTrue(brain.search("asha").isEmpty())
    }

    // ── tasks ──

    @Test
    fun openTasksPutDatedFirstSoonestFirst() {
        brain.addTask("No date")
        brain.addTask("Later", dueAt = 5_000_000)
        brain.addTask("Sooner", dueAt = 2_000_000)
        assertEquals(listOf("Sooner", "Later", "No date"), brain.openTasks().map { it.title })
    }

    @Test
    fun dueByIncludesOverdueAndTodayOnly() {
        brain.addTask("Overdue", dueAt = 500_000)
        brain.addTask("Today", dueAt = 1_500_000)
        brain.addTask("Next week", dueAt = 9_000_000)
        brain.addTask("Undated")
        assertEquals(listOf("Overdue", "Today"), brain.tasksDueBy(2_000_000).map { it.title })
    }

    @Test
    fun completingMovesATaskToDoneAndBack() {
        val t = brain.addTask("Send the deck")
        now += 5; brain.setTaskDone(t.id, true)
        assertTrue(brain.openTasks().isEmpty())
        assertEquals(now, brain.doneTasks().single().completedAt)
        brain.setTaskDone(t.id, false)
        assertEquals("Send the deck", brain.openTasks().single().title)
        assertNull(brain.task(t.id)!!.completedAt)
    }

    @Test
    fun updateTaskChangesOnlyWhatIsGivenAndReindexes() {
        val t = brain.addTask("Call bank", dueAt = 100)
        brain.updateTask(t.id, title = "Call the bank about the card")
        val u = brain.task(t.id)!!
        assertEquals("Call the bank about the card", u.title)
        assertEquals(100L, u.dueAt)
        assertTrue(brain.search("card").any { it.refId == t.id })
        brain.updateTask(t.id, clearDue = true)
        assertNull(brain.task(t.id)!!.dueAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlankTaskIsRefused() {
        brain.addTask("   ")
    }

    // ── reminders ──

    @Test
    fun onlyDueUndeliveredRemindersFire() {
        val early = brain.addReminder("Call the bank", at = 900_000)
        brain.addReminder("Later thing", at = 2_000_000)
        assertEquals(listOf(early.id), brain.dueReminders().map { it.id })
        brain.markDelivered(early.id)
        assertTrue(brain.dueReminders().isEmpty())
        assertEquals(1, brain.upcomingReminders().size)
    }

    // ── search ──

    @Test
    fun oneSearchFindsEveryKindWithPrefixes() {
        val c = brain.createConversation("Planning")
        brain.addMessage(c.id, ChatTurn.USER, "We decided the budget is 40k")
        brain.addTask("Review budget spreadsheet")
        brain.addNote("Budget notes", "Rent, food, travel")
        brain.remember("Budget reviews happen on Fridays")
        val kinds = brain.search("budg").map { it.kind }.toSet()
        assertEquals(setOf("conversation", "task", "note", "memory"), kinds)
        // A message hit resolves to its conversation, with its real title.
        assertEquals("Planning", brain.search("decided").single().title)
    }

    @Test
    fun hostileSearchInputIsHarmless() {
        brain.addTask("Pay rent")
        // FTS operators and quotes are just words now: no crash, no changed meaning.
        brain.search("rent\" OR * NEAR( -")                       // must not throw
        assertTrue(brain.search("\"rent\" *").any { it.title == "Pay rent" })
        assertTrue(brain.search("pay AND rent").isEmpty())        // "and" is a word, not an operator
        assertTrue(brain.search("   ").isEmpty())
        assertTrue(brain.search("!!!").isEmpty())
    }

    @Test
    fun ftsQueryQuotesEveryWord() {
        assertEquals("\"call\"* \"bank\"*", Brain.ftsQuery("Call the... bank".replace("the... ", "")))
        assertNull(Brain.ftsQuery("  ?? "))
    }

    // ── activity ──

    @Test
    fun activityIsNewestFirst() {
        brain.log("task", "Added a task"); now += 1
        brain.log("reminder", "Set a reminder")
        assertEquals(listOf("Set a reminder", "Added a task"), brain.activity().map { it.summary })
    }

    // ── persistence + import ──

    @Test
    fun survivesReopeningTheFile() {
        val f = tmp.root.resolve("brain.db")
        Brain.open(f).use { it.addTask("Persist me"); it.remember("I like tea") }
        Brain.open(f).use {
            assertEquals("Persist me", it.openTasks().single().title)
            assertEquals("I like tea", it.memories().single().text)
        }
    }

    @Test
    fun legacyChatsAndFactsImportOnceInOrderWithABackup() {
        val legacy = tmp.root.resolve("chat.json")
        ChatStore(legacy).save(
            ChatStore.State(
                conversations = listOf(
                    ChatStore.Conversation("old", "Old chat", listOf(ChatTurn(ChatTurn.USER, "first"), ChatTurn(ChatTurn.ASSISTANT, "second")), 100),
                    ChatStore.Conversation("new", "New chat", listOf(ChatTurn(ChatTurn.USER, "hello")), 200),
                ),
                facts = listOf("Likes tea", "likes TEA", "Works 9-6"),
            ),
        )
        val f = tmp.root.resolve("brain.db")
        Brain.open(f).use { b ->
            val r = LegacyImport.run(b, legacy)!!
            assertEquals(2, r.conversations)
            assertEquals(3, r.messages)
            assertEquals(2, r.memories) // the duplicate is not doubled
            assertEquals(listOf("New chat", "Old chat"), b.conversations().map { it.title })
            assertEquals(listOf("first", "second"), b.messages("old").map { it.content })
            assertTrue(b.activity().any { it.kind == "import" })
            // Second run: nothing happens (brain not empty; file already moved aside).
            assertNull(LegacyImport.run(b, legacy))
        }
        assertFalse(legacy.exists())
        assertNotNull(tmp.root.listFiles()!!.firstOrNull { it.name == "chat.json.imported" })
    }
}
