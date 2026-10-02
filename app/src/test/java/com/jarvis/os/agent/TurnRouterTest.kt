package com.jarvis.os.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRouterTest {

    // ── should go through the new native tool-calling loop ──────────────────
    @Test
    fun routesClearTaskReminderNoteAndRecallRequests() {
        val yes = listOf(
            "remind me to call mom tomorrow at 5",
            "remind me to text dad later",
            "add a task to buy milk",
            "add buy milk to my to-do list",
            "what's on my to-do list",
            "mark the report task as done",
            "complete the task about the deck",
            "delete the old report task",
            "save a note about the trip",
            "note that the wifi password changed",
            "what did we decide about the budget",
            "what's pending right now",
        )
        yes.forEach { assertTrue(it, TurnRouter.useNativeTools(it)) }
    }

    // ── must stay on the old marker system — no tool exists for these yet ───
    @Test
    fun excludesCalendarAlarmScreenAndFileRequests() {
        val no = listOf(
            "add a meeting with the team tomorrow at 3",
            "put lunch with Priya on my calendar",
            "set an alarm for 7am",
            "set a timer for 10 minutes",
            "wake me up at six",
            "play Blinding Lights",
            "open Spotify",
            "order milk on blinkit",
            "search for lo-fi beats on youtube",
            "open whatsapp and message mom",
            "take a screenshot",
            "make a pdf of this",
            "remember that my wifi password is hunter2",
            "forget what you know about my dog",
        )
        no.forEach { assertFalse(it, TurnRouter.useNativeTools(it)) }
    }

    // ── genuinely ambiguous phrasing: the common, valuable case must win ────
    @Test
    fun aReminderToCallOrTextSomeoneStillRoutesToTheNewPath() {
        // The single most common reminder phrasing ("remind me to call/text X")
        // must not be swallowed by a broad "call"/"text" screen-action exclusion —
        // see the class doc for why those verbs are deliberately not excluded.
        assertTrue(TurnRouter.useNativeTools("remind me to call the bank at 5"))
        assertTrue(TurnRouter.useNativeTools("remind me to text the landlord tomorrow"))
        assertTrue(TurnRouter.useNativeTools("remind me to message HR about the form"))
    }

    // ── ordinary conversation: no tool needed, but nothing dangerous either ──
    @Test
    fun ordinaryConversationDoesNotMatchEither() {
        val chat = listOf("how are you", "what's today's date", "tell me a joke", "hi jarvis")
        chat.forEach { assertFalse(it, TurnRouter.useNativeTools(it)) }
    }
}
