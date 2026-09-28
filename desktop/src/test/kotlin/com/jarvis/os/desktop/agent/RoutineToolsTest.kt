package com.jarvis.os.desktop.agent

import com.jarvis.os.desktop.StartWithWindows
import com.jarvis.os.desktop.DesktopTurn
import com.jarvis.os.desktop.brain.Brain
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Phase 6 (S9): routines through the agent's tools, in the brain, and their plain-text delivery. */
class RoutineToolsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private var now = at("2026-09-28T14:00")
    private val brain = Brain.inMemory { now }
    private val tools = ToolBox(brain, object : ToolBox.Host {
        override fun openUrl(url: String) = true
        override fun openApp(name: String): String? = null
        override fun clipboardText(): String? = null
    }, { now }, zone)
    @After fun close() = brain.close()

    private fun run(name: String, args: String) = runBlocking { tools.execute(name, args, sourceConversation = "c1") }

    @Test
    fun everyWeekdayAtEightBriefMe_S9() {
        val r = run("create_routine", """{"name":"Morning brief","instruction":"Brief me: today's tasks and reminders.","days":"weekdays","time":"08:00"}""")
        assertTrue(r.forModel, r.ok)
        assertEquals("Routine “Morning brief” · Weekdays at 08:00 · next Tomorrow 08:00", r.summary)
        val routine = brain.routines().single()
        assertEquals(at("2026-09-29T08:00"), routine.nextRun)
        assertTrue(routine.enabled && routine.speak)
        assertEquals("c1", routine.sourceConversation)
        // Not due yet; due once its time comes.
        assertTrue(brain.dueRoutines(now).isEmpty())
        assertEquals(listOf(routine.id), brain.dueRoutines(at("2026-09-29T08:00")).map { it.id })
    }

    @Test
    fun anUnreadableScheduleIsRefusedNotGuessed() {
        assertFalse(run("create_routine", """{"name":"x","instruction":"y","days":"sometimes","time":"8"}""").ok)
        assertFalse(run("create_routine", """{"name":"x","instruction":"y","days":"daily","time":"later"}""").ok)
        assertFalse(run("create_routine", """{"name":"","instruction":"y","days":"daily","time":"8"}""").ok)
        assertTrue(brain.routines().isEmpty())
    }

    @Test
    fun creatingAndDeletingAreBothUndoable() {
        val made = run("create_routine", """{"name":"Weekly review","instruction":"Review my week.","days":"sun","time":"18:00"}""")
        assertEquals("Removed the routine", tools.undo(made.undo!!))
        assertTrue(brain.routines().isEmpty())

        run("create_routine", """{"name":"Weekly review","instruction":"Review my week.","days":"sun","time":"18:00"}""")
        val gone = run("delete_routine", """{"routine":"weekly"}""")
        assertTrue(gone.ok)
        assertTrue(brain.routines().isEmpty())
        assertEquals("Restored “Weekly review”", tools.undo(gone.undo!!))
        assertEquals(1, brain.routines().size)
        assertFalse(run("delete_routine", """{"routine":"nothing"}""").ok)
    }

    @Test
    fun listingShowsWhenEachRunsNext() {
        run("create_routine", """{"name":"Morning brief","instruction":"Brief me.","days":"daily","time":"7:30"}""")
        val list = JSONObject(run("list_routines", "{}").forModel).getJSONArray("routines")
        assertEquals("Every day at 07:30", list.getJSONObject(0).getString("when"))
        assertEquals("Tomorrow 07:30", list.getJSONObject(0).getString("next"))
    }

    @Test
    fun pausingKeepsItAndARunMovesItOn() {
        val r = brain.addRoutine("Brief", "Brief me.", com.jarvis.os.desktop.brain.Schedule.parse("daily", "8")!!, at("2026-09-29T08:00"))
        brain.setRoutineEnabled(r.id, false)
        assertTrue(brain.dueRoutines(at("2026-09-30T00:00")).isEmpty())
        brain.setRoutineEnabled(r.id, true, nextRun = at("2026-09-30T08:00"))
        brain.markRoutine(r.id, at("2026-09-30T08:00"), at("2026-10-01T08:00"))
        val after = brain.routine(r.id)!!
        assertEquals(at("2026-10-01T08:00"), after.nextRun)
        assertEquals(at("2026-09-30T08:00"), after.lastRun)
        // A skip records no run.
        brain.markRoutine(r.id, null, at("2026-10-02T08:00"))
        assertEquals(at("2026-09-30T08:00"), brain.routine(r.id)!!.lastRun)
        brain.setRoutineSpeak(r.id, false)
        assertFalse(brain.routine(r.id)!!.speak)
    }

    // ── delivery ──

    @Test
    fun aBriefBecomesPlainTextForANotificationOrSpeech() {
        val md = "## Your day\n\n**3 tasks** due:\n- Send the *deck* to Priya\n* Book the review\n\n---\nSee [the doc](https://x.y) and `code`."
        assertEquals("Your day\n3 tasks due:\n• Send the deck to Priya\n• Book the review\nSee the doc and code.", DesktopTurn.plain(md))
    }

    @Test
    fun theRoutineNoteTellsTheModelNobodyIsWatching() {
        val n = DesktopTurn.routineNote("Morning brief", manual = false)
        assertTrue(n.contains("Morning brief"))
        assertTrue(n.contains("do not ask questions"))
        assertTrue(n.contains("will be declined"))
        assertTrue(DesktopTurn.routineNote("x", manual = true).contains("run now"))
        // A routine's first line is shown as a chip, like an attachment.
        assertEquals(listOf("⏰ Morning brief") to "Brief me.", DesktopTurn.splitAttachments("⏰ Morning brief\nBrief me."))
    }

    @Test
    fun startWithWindowsLaunchesTheExeHiddenInTheTray() {
        assertEquals("\"C:\\Program Files\\JARVIS\\JARVIS.exe\" --background", StartWithWindows.command(File("C:\\Program Files\\JARVIS\\JARVIS.exe")))
        // A development run (java.exe) never offers it.
        assertNull(StartWithWindows.exe())
    }
}
