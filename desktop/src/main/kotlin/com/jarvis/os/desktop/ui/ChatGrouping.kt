package com.jarvis.os.desktop.ui

import com.jarvis.os.desktop.agent.ActionCard
import com.jarvis.os.desktop.brain.Brain
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The decluttering logic behind the redesigned sidebar and chat, kept free of Compose so it can be tested.
 *
 * Two jobs: (1) a long flat list of chats becomes a short one under day headings, capped, so the sidebar shows
 * what you were just doing instead of everything you ever did; (2) the tool "step" cards a turn leaves behind
 * (searched the web, opened a page…) collapse into one quiet line instead of a stack of boxes between the
 * words that matter.
 */
object ChatGrouping {

    /** How many of the newest chats the sidebar shows before "See all". */
    const val VISIBLE_CHATS = 6

    data class DayGroup(val label: String, val chats: List<Brain.Conversation>)

    /** Newest first, under "Today", "Yesterday" and "Earlier" (empty groups are left out). Days are the reader's local days. */
    fun byDay(chats: List<Brain.Conversation>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<DayGroup> {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        fun dayOf(c: Brain.Conversation) = LocalDate.ofInstant(Instant.ofEpochMilli(c.updated), zone)
        val sorted = chats.sortedByDescending { it.updated }
        val now = sorted.filter { !dayOf(it).isBefore(today) }                      // today (or "in the future" from clock skew)
        val yesterday = sorted.filter { dayOf(it) == today.minusDays(1) }
        val earlier = sorted.filter { dayOf(it).isBefore(today.minusDays(1)) }
        return listOf(DayGroup("Today", now), DayGroup("Yesterday", yesterday), DayGroup("Earlier", earlier)).filter { it.chats.isNotEmpty() }
    }

    /** Keeps the newest [cap] chats across the groups, in order. Returns the trimmed groups and how many chats were left out. */
    fun capped(groups: List<DayGroup>, cap: Int = VISIBLE_CHATS): Pair<List<DayGroup>, Int> {
        var left = cap
        val out = ArrayList<DayGroup>()
        for (g in groups) {
            if (left <= 0) break
            val take = g.chats.take(left)
            out += DayGroup(g.label, take)
            left -= take.size
        }
        return out to (groups.sumOf { it.chats.size } - out.sumOf { it.chats.size })
    }

    /** One entry in the conversation as drawn: a message, or a run of consecutive tool steps shown as one line. */
    sealed interface Item {
        data class Message(val turn: Brain.Message) : Item
        data class Steps(val turns: List<Brain.Message>) : Item
    }

    /** Consecutive step turns (decodable action cards) merge into one [Item.Steps]; everything else stays a message. */
    fun items(turns: List<Brain.Message>): List<Item> {
        val out = ArrayList<Item>()
        val run = ArrayList<Brain.Message>()
        fun flush() { if (run.isNotEmpty()) { out += Item.Steps(run.toList()); run.clear() } }
        for (t in turns) {
            if (t.role == ActionCard.ROLE && ActionCard.decode(t.content) != null) run += t
            else { flush(); out += Item.Message(t) }
        }
        flush()
        return out
    }

    private val URL = Regex("""https?://(?:www\.)?([^/\s?#]+)\S*""")
    private val QUERY = Regex(""" for “[^”]*”""")

    /** A step's summary cut down to a few words: the search query and sources go, a long URL becomes its host. */
    fun compactStep(summary: String): String {
        var t = QUERY.replace(summary, "")
        t = t.substringBefore(" · ")
        t = URL.replace(t) { it.groupValues[1] }
        t = t.trim().trimEnd('.', '·', ' ')
        return if (t.length > 48) t.take(47).trimEnd() + "…" else t
    }

    /** "Searched the web · Opened youtube.com" — the first [max] distinct steps, then "+N more". */
    fun stepsLine(summaries: List<String>, max: Int = 2): String {
        val distinct = summaries.map(::compactStep).filter { it.isNotBlank() }.distinct()
        val shown = distinct.take(max).joinToString(" · ")
        val more = distinct.size - max
        return if (more > 0) "$shown · +$more more" else shown
    }
}
