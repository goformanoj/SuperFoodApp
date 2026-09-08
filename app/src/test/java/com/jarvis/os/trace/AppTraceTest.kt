package com.jarvis.os.trace

import com.jarvis.os.debug.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trace is shared off the device, so its segmentation and — above all — its
 * redaction are contracts, not conveniences. These run off-device against the pure
 * builder.
 */
class AppTraceTest {

    private fun e(stage: DebugLog.Stage, detail: String, t: Long = 0L) =
        DebugLog.Entry(t, stage, detail)

    @Test
    fun `a turn starts at each HEARD and gathers its steps`() {
        val turns = AppTrace.build(listOf(
            e(DebugLog.Stage.HEARD, "open blinkit"),
            e(DebugLog.Stage.REPLY, "<<OPEN|Blinkit>>"),
            e(DebugLog.Stage.SCREEN, "on screen: search"),
            e(DebugLog.Stage.HEARD, "add milk"),
            e(DebugLog.Stage.REPLY, "<<TAP|Search>>"),
        ))

        assertEquals(2, turns.size)
        assertEquals("open blinkit", turns[0].goal)
        assertEquals(2, turns[0].steps.size)
        assertEquals("add milk", turns[1].goal)
        assertEquals(1, turns[1].steps.size)
    }

    @Test
    fun `entries before the first HEARD are dropped`() {
        val turns = AppTrace.build(listOf(
            e(DebugLog.Stage.SESSION, "app started"),
            e(DebugLog.Stage.THINK, "warming up"),
            e(DebugLog.Stage.HEARD, "what time is it"),
            e(DebugLog.Stage.SPOKE, "it is noon"),
        ))

        assertEquals(1, turns.size)
        assertEquals("what time is it", turns[0].goal)
        assertEquals(1, turns[0].steps.size)
    }

    @Test
    fun `outcome — met, stuck, asked, failed, unknown`() {
        fun outcome(vararg steps: DebugLog.Entry) =
            AppTrace.build(listOf(e(DebugLog.Stage.HEARD, "do it")) + steps).single().outcome

        assertEquals(AppTrace.Outcome.OK, outcome(e(DebugLog.Stage.SCREEN, "agent says the goal is met")))
        assertEquals(AppTrace.Outcome.STUCK, outcome(e(DebugLog.Stage.SCREEN, "agent is stuck: no field")))
        assertEquals(AppTrace.Outcome.ASKED, outcome(e(DebugLog.Stage.SCREEN, "agent stopped to ask: which chat?")))
        assertEquals(AppTrace.Outcome.FAILED, outcome(e(DebugLog.Stage.ERROR, "agent step failed: boom")))
        assertEquals(AppTrace.Outcome.UNKNOWN, outcome(e(DebugLog.Stage.SPOKE, "done")))
    }

    @Test
    fun `a stuck note wins over an error on the way to it`() {
        val turn = AppTrace.build(listOf(
            e(DebugLog.Stage.HEARD, "add milk on blinkit"),
            e(DebugLog.Stage.ERROR, "agent step failed: no editable field"),
            e(DebugLog.Stage.SCREEN, "agent is stuck: this screen doesn't show the control"),
        )).single()

        assertEquals(AppTrace.Outcome.STUCK, turn.outcome)
    }

    @Test
    fun `otp and card-like digit runs are scrubbed from goal and steps`() {
        val turn = AppTrace.build(listOf(
            e(DebugLog.Stage.HEARD, "remember the code 458213"),
            e(DebugLog.Stage.REPLY, "card 4111222233334444 saved"),
        )).single()

        assertFalse(turn.goal.contains("458213"))
        assertFalse(turn.steps[0].detail.contains("4111"))
        assertTrue(turn.goal.contains("***"))
    }

    @Test
    fun `short numbers like quantities survive`() {
        val turn = AppTrace.build(listOf(
            e(DebugLog.Stage.HEARD, "add 2 packs of milk"),
        )).single()

        assertTrue(turn.goal.contains("2 packs"))
    }

    @Test
    fun `json is well-formed and escapes quotes and newlines`() {
        val json = AppTrace.exportJson(listOf(
            e(DebugLog.Stage.HEARD, "search \"milk\""),
            e(DebugLog.Stage.SCREEN, "line1\nline2"),
        ), createdAt = 1_725_000_000_000L)

        assertTrue(json.startsWith("{\"schema\":\"jarvis.app-trace/1\""))
        assertTrue(json.contains("\"createdAt\":1725000000000"))
        // the quote in the goal is escaped, and the newline in the detail is \n,
        // so the payload stays on one logical line with no raw control chars.
        assertTrue(json.contains("""search \"milk\""""))
        assertTrue(json.contains("""line1\nline2"""))
        assertFalse(json.contains('\n'))
    }

    @Test
    fun `an empty log yields an empty turn list`() {
        val json = AppTrace.exportJson(emptyList(), createdAt = 0L)
        assertEquals("{\"schema\":\"jarvis.app-trace/1\",\"createdAt\":0,\"turns\":[]}", json)
    }
}
