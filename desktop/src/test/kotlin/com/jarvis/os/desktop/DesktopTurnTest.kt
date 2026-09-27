package com.jarvis.os.desktop

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
    fun contextOmitsEmptyMemory() {
        assertFalse(DesktopTurn.context("Sunday", "").endsWith("\n\n"))
    }
}
