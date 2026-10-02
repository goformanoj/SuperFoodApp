package com.jarvis.os.desktop.ui

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.agent.ActionCard
import com.jarvis.os.desktop.brain.Brain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatGroupingTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val now = ms(2026, 10, 2, 21)
    private fun chat(id: String, updated: Long) = Brain.Conversation(id, "chat $id", null, false, false, updated, updated)

    @Test
    fun chatsAreGroupedByTheReadersDayNewestFirst() {
        val groups = ChatGrouping.byDay(
            listOf(chat("old", ms(2026, 9, 20)), chat("y", ms(2026, 10, 1, 23)), chat("t1", ms(2026, 10, 2, 8)), chat("t2", ms(2026, 10, 2, 20))),
            now, zone,
        )
        assertEquals(listOf("Today", "Yesterday", "Earlier"), groups.map { it.label })
        assertEquals(listOf("t2", "t1"), groups[0].chats.map { it.id })
        assertEquals(listOf("y"), groups[1].chats.map { it.id })
        assertEquals(listOf("old"), groups[2].chats.map { it.id })
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        val groups = ChatGrouping.byDay(listOf(chat("t", ms(2026, 10, 2, 9))), now, zone)
        assertEquals(listOf("Today"), groups.map { it.label })
        assertTrue(ChatGrouping.byDay(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun theDayBoundaryIsLocalMidnightNotUtc() {
        // 00:30 local on the 2nd is still the 1st in UTC: it must read as Today for this reader.
        val justAfterMidnight = ZonedDateTime.of(2026, 10, 2, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        val justBefore = ZonedDateTime.of(2026, 10, 1, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val g = ChatGrouping.byDay(listOf(chat("a", justAfterMidnight), chat("b", justBefore)), now, zone)
        assertEquals("Today", g[0].label); assertEquals("a", g[0].chats.single().id)
        assertEquals("Yesterday", g[1].label); assertEquals("b", g[1].chats.single().id)
    }

    @Test
    fun theSidebarKeepsOnlyTheNewestSixAndCountsTheRest() {
        val chats = (1..10).map { chat("c$it", ms(2026, 10, 2, 8) + it * 60_000L) }
        val (shown, hidden) = ChatGrouping.capped(ChatGrouping.byDay(chats, now, zone))
        assertEquals(6, shown.sumOf { it.chats.size })
        assertEquals(4, hidden)
        assertEquals("c10", shown[0].chats.first().id)          // newest first
        assertEquals(0, ChatGrouping.capped(ChatGrouping.byDay(chats.take(3), now, zone)).second)
    }

    @Test
    fun theCapSpansGroupsInOrder() {
        val chats = listOf(chat("t", ms(2026, 10, 2, 9))) + (1..8).map { chat("y$it", ms(2026, 10, 1, 8) + it * 1000L) }
        val (shown, hidden) = ChatGrouping.capped(ChatGrouping.byDay(chats, now, zone), cap = 4)
        assertEquals(listOf(1, 3), shown.map { it.chats.size })
        assertEquals(5, hidden)
    }

    // --- steps -----------------------------------------------------------------

    private fun msg(id: String, role: String, content: String) = Brain.Message(id, "c", role, content, 0L)
    private fun step(summary: String) = msg("s-$summary", ActionCard.ROLE, ActionCard(summary, true, null).encode())
    private fun say(role: String, text: String) = msg("m-$text", role, text)

    @Test
    fun consecutiveStepsMergeIntoOneLineAndMessagesStayWhereTheyAre() {
        val turns = listOf(say(ChatTurn.USER, "play it"), step("Searched the web"), step("Opened youtube.com"), say(ChatTurn.ASSISTANT, "Done"), step("Added a task"))
        val items = ChatGrouping.items(turns)
        assertEquals(4, items.size)
        assertTrue(items[0] is ChatGrouping.Item.Message)
        assertEquals(2, (items[1] as ChatGrouping.Item.Steps).turns.size)
        assertTrue(items[2] is ChatGrouping.Item.Message)
        assertEquals(1, (items[3] as ChatGrouping.Item.Steps).turns.size)
    }

    @Test
    fun aStepTurnThatCannotBeReadIsKeptAsAMessageNotSwallowed() {
        val broken = msg("b", ActionCard.ROLE, "not json")
        val items = ChatGrouping.items(listOf(broken))
        assertTrue(items.single() is ChatGrouping.Item.Message)
    }

    @Test
    fun aStepIsCutDownToAFewWords() {
        assertEquals("Searched the web", ChatGrouping.compactStep("Searched the web for “Ajit Singh most popular playlist YouTube” · youtube.com, music.youtube.com"))
        assertEquals("Opened youtube.com", ChatGrouping.compactStep("Opened https://www.youtube.com/playlist?list=PLOZ67tlyTaWq7xmJYR0Im1fwtlhc0TO_6"))
        assertEquals("Added task “Buy milk”", ChatGrouping.compactStep("Added task “Buy milk” · Tomorrow 09:00"))
        assertTrue(ChatGrouping.compactStep("x".repeat(200)).length <= 48)
    }

    @Test
    fun theOneLineSummaryNamesTheFirstTwoDistinctStepsThenCountsTheRest() {
        assertEquals(
            "Searched the web · Opened youtube.com",
            ChatGrouping.stepsLine(listOf("Searched the web for “x” · a.com", "Opened https://www.youtube.com/x")),
        )
        assertEquals("Searched the web", ChatGrouping.stepsLine(listOf("Searched the web for “a”", "Searched the web for “b”")))
        assertEquals("A · B · +2 more", ChatGrouping.stepsLine(listOf("A", "B", "C", "D")))
    }

    @Test
    fun tokenCountsAreShortEnoughForASmallCard() {
        assertEquals("1.99M", compactTokens(1_997_191))
        assertEquals("2M", compactTokens(2_000_000))
        assertEquals("52k", compactTokens(52_300))
        assertEquals("743", compactTokens(743))
        assertEquals("9,999", compactTokens(9_999))
    }
}
