package com.jarvis.os.desktop

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.data.MemoryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTurnTest {

    @Test
    fun plainReplyPassesThroughUntouched() {
        val r = DesktopTurn.process("The capital of France is Paris.")
        assertEquals("The capital of France is Paris.", r.text)
        assertFalse(r.wantedPhoneAction)
        assertTrue(r.memory.isEmpty())
    }

    @Test
    fun phoneActionIsNeverShownAndIsExplained() {
        val r = DesktopTurn.process("Opening WhatsApp. <<OPEN|WhatsApp>>")
        assertTrue(r.wantedPhoneAction)
        assertFalse(r.text.contains("<<"))
        assertTrue(r.text.startsWith("Opening WhatsApp."))
        assertTrue(r.text.endsWith(DesktopTurn.PHONE_ONLY_NOTE))
    }

    @Test
    fun markerOnlyReplyBecomesTheNoteAlone() {
        val r = DesktopTurn.process("<<TAP|Send>>")
        assertEquals(DesktopTurn.PHONE_ONLY_NOTE, r.text)
    }

    @Test
    fun lowercaseAndSpacedPhoneMarkersAreStillCaught() {
        assertTrue(DesktopTurn.process("ok << alarm|07:00>>").wantedPhoneAction)
    }

    @Test
    fun fileBodyIsKeptBecauseItIsPlainText() {
        val r = DesktopTurn.process("<<FILE|txt|Notes>>\nBuy milk\n<<ENDFILE>>")
        assertFalse(r.wantedPhoneAction)
        assertEquals("Buy milk", r.text)
    }

    @Test
    fun rememberIsParsedAndStripped() {
        val r = DesktopTurn.process("Got it. <<REMEMBER|My sister is Asha>>")
        assertEquals("Got it.", r.text)
        assertEquals(listOf(MemoryAction.Remember("My sister is Asha")), r.memory)
    }

    @Test
    fun rememberDedupesCaseInsensitively() {
        val out = DesktopTurn.applyMemory(listOf("Likes tea"), listOf(MemoryAction.Remember("likes TEA")))
        assertEquals(listOf("Likes tea"), out)
    }

    @Test
    fun rememberDropsTheOldestAtTheCap() {
        val full = (1..DesktopTurn.MAX_FACTS).map { "fact $it" }
        val out = DesktopTurn.applyMemory(full, listOf(MemoryAction.Remember("newest")))
        assertEquals(DesktopTurn.MAX_FACTS, out.size)
        assertEquals("fact 2", out.first())
        assertEquals("newest", out.last())
    }

    @Test
    fun forgetRemovesEveryFactMentioningIt() {
        val out = DesktopTurn.applyMemory(
            listOf("Sister is Asha", "Asha likes cats", "Works at Acme"),
            listOf(MemoryAction.Forget("asha")),
        )
        assertEquals(listOf("Works at Acme"), out)
    }

    @Test
    fun blankForgetRemovesNothing() {
        val facts = listOf("a", "b")
        assertEquals(facts, DesktopTurn.applyMemory(facts, listOf(MemoryAction.Forget("  "))))
    }

    @Test
    fun contextTellsTheModelItIsOnTheDesktopAndCarriesMemory() {
        val c = DesktopTurn.context("Sunday", "What you know about this user")
        assertTrue(c.contains("desktop app"))
        assertTrue(c.contains("do not emit any device-action marker"))
        assertTrue(c.endsWith("What you know about this user"))
    }

    @Test
    fun contextSaysSoWhenFileAccessIsOffSoTheModelDoesNotQuizTheUser() {
        val off = DesktopTurn.context("Sunday", "", laptopFiles = false)
        assertTrue(off.contains("Settings → Permissions → Laptop files"))
        assertTrue(off.contains("do not ask which drive, folder or file type"))
        assertFalse("must not promise file tools it was not given", off.contains("find files and look inside folders"))
    }

    @Test
    fun contextIsQuietAboutFilesWhenAccessIsOn() {
        val on = DesktopTurn.context("Sunday", "")
        assertFalse(on.contains("NOT allowed"))
        assertTrue(on.contains("find files and look inside folders"))
    }

    @Test
    fun titleIsTheFirstUserLine() {
        val turns = listOf(ChatTurn(ChatTurn.USER, "\n  Plan my   morning\nwith details"), ChatTurn(ChatTurn.ASSISTANT, "ok"))
        assertEquals("Plan my morning", DesktopTurn.titleFor(turns))
    }

    @Test
    fun longTitlesAreTrimmedWithAnEllipsis() {
        val t = DesktopTurn.titleFor(listOf(ChatTurn(ChatTurn.USER, "a".repeat(100))))
        assertEquals(DesktopTurn.TITLE_MAX, t.length)
        assertTrue(t.endsWith("…"))
    }

    @Test
    fun renamedTitlesAreTidied() {
        assertEquals("Budget plan", DesktopTurn.cleanTitle("  Budget \n  plan "))
        assertEquals(null, DesktopTurn.cleanTitle("   \n "))
        assertEquals(DesktopTurn.TITLE_MAX, DesktopTurn.cleanTitle("x".repeat(80))!!.length)
    }

    @Test
    fun noUserTurnMeansNewChat() {
        assertEquals("New chat", DesktopTurn.titleFor(emptyList()))
    }

    @Test
    fun contextOmitsEmptyMemory() {
        assertFalse(DesktopTurn.context("Sunday", "").endsWith("\n\n"))
    }

    // ── Phase 5: attachments ride on the message ──

    @Test
    fun attachmentLinesAreSplitFromTheWords() {
        val (marks, text) = DesktopTurn.splitAttachments("📎 lease.pdf\n🖥 Screenshot of my screen\nWhat's the notice period?")
        assertEquals(listOf("📎 lease.pdf", "🖥 Screenshot of my screen"), marks)
        assertEquals("What's the notice period?", text)
        // A message with no attachments is all words, even one that mentions a clip later.
        assertEquals(emptyList<String>() to "hi\n📎 not a mark here", DesktopTurn.splitAttachments("hi\n📎 not a mark here"))
    }

    @Test
    fun theTitleComesFromTheQuestionNotTheAttachment() {
        assertEquals("Summarise lease.pdf.", DesktopTurn.titleFor(listOf(ChatTurn(ChatTurn.USER, "📎 lease.pdf\nSummarise lease.pdf."))))
    }

    @Test
    fun theCalendarNamesRealWeekdaysForThisWeekAndNext() {
        // Monday 28 September 2026 (the live bug: "by Friday" became Thursday 1 Oct).
        val w = DesktopTurn.weekAhead(java.time.LocalDate.of(2026, 9, 28))
        assertTrue(w, w.startsWith("Rest of this week: Tue 29 Sep, Wed 30 Sep, Thu 1 Oct, Fri 2 Oct, Sat 3 Oct, Sun 4 Oct."))
        assertTrue(w.endsWith("Next week: Mon 5 Oct, Tue 6 Oct, Wed 7 Oct, Thu 8 Oct, Fri 9 Oct, Sat 10 Oct, Sun 11 Oct."))
        // On a Sunday there is no rest of the week.
        assertTrue(DesktopTurn.weekAhead(java.time.LocalDate.of(2026, 10, 4)).startsWith("Next week: Mon 5 Oct"))
        assertTrue(DesktopTurn.context("Monday", "", today = java.time.LocalDate.of(2026, 9, 28)).contains("Fri 2 Oct"))
    }

    @Test
    fun contextNamesTheAttachedDocumentsAndTheKnowledgeTools() {
        val c = DesktopTurn.context("Sunday", "", listOf("lease.pdf (12 pages)"))
        assertTrue(c.contains("Documents attached to this chat"))
        assertTrue(c.contains("lease.pdf (12 pages)"))
        assertTrue(c.contains("search the web"))
        assertFalse(DesktopTurn.context("Sunday", "").contains("Documents attached"))
    }
}
