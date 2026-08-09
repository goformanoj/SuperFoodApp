package com.jarvis.os.assistant

import com.jarvis.os.alarm.AlarmAction
import com.jarvis.os.alarm.AlarmActions
import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenMatch
import com.jarvis.os.control.ScreenStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 50 realistic "prompt → what JARVIS should do" scenarios for screen control,
 * across music, shopping, messaging, navigation and safety.
 *
 * HONEST BOUNDARY. Each case asserts the **post-guard executed plan** — what
 * survives the deterministic chain the engine runs (`ScreenActions.parse →
 * AskGuard → SendGuard → SpendGuard`, or `AgentLoop.parseMove`/`isErrand` for
 * errands). It pairs a prompt with *the plan a correct model would emit* and checks
 * the pipeline executes it correctly and safely. It does NOT assert what the LLM
 * emits for a prompt, nor whether a tap lands in the real app — those are the
 * device-only ceiling covered by `docs/SCREEN_CONTROL_EVAL.md`.
 */
class ScreenControlAccuracyScenariosTest {

    // Post-guard executed plan for a one-shot turn (mirrors AssistantEngine.ask()).
    private fun plan(prompt: String, reply: String): List<ScreenStep> {
        val parsed = ScreenActions.parse(reply)
        val asked = AskGuard.apply(parsed.clean, parsed.steps)
        val guarded = SendGuard.apply(prompt, asked)
        return SpendGuard.apply(prompt, guarded)
    }

    // Post-guard alarm actions (mirrors the AlarmActions.parse → AlarmGuard.apply step).
    private fun alarms(prompt: String, reply: String): List<AlarmAction> =
        AlarmGuard.apply(prompt, AlarmActions.parse(reply).second)

    // Same, with JARVIS's previous turn as context (the two-turn alarm confirm).
    private fun alarms(prompt: String, priorPrompt: String, reply: String): List<AlarmAction> =
        AlarmGuard.apply(prompt, AlarmActions.parse(reply).second, priorPrompt)

    private fun steps(reply: String) = ScreenActions.parse(reply).steps

    // ============================== A · Music & media (10) ==============================

    @Test
    fun music_scenarios() {
        // A1 play a specific song — search then PICK the not-yet-existing result.
        assertEquals(
            listOf(
                ScreenStep.Open("YouTube Music"), ScreenStep.Tap("Search"),
                ScreenStep.Type("Blinding Lights"), ScreenStep.Enter,
                ScreenStep.Pick("the first Blinding Lights track"),
            ),
            plan("play blinding lights", "<<OPEN|YouTube Music>> <<TAP|Search>> <<TYPE|Blinding Lights>> <<ENTER>> <<PICK|the first Blinding Lights track>>"),
        )
        // A2 play the first result.
        assertEquals(5, plan("play the first result for lo-fi beats", "<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|lo-fi beats>> <<ENTER>> <<PICK|the first result>>").size)
        // A3 play a playlist.
        assertEquals(5, plan("play my workout playlist", "<<OPEN|Spotify>> <<TAP|Search>> <<TYPE|workout playlist>> <<ENTER>> <<PICK|my workout playlist>>").size)
        // A4 queue a song after this — no queue primitive, it's ordinary taps; both survive.
        assertEquals(
            listOf(ScreenStep.Tap("More options"), ScreenStep.Tap("Add to queue")),
            plan("queue this song after the current one", "<<TAP|More options>> <<TAP|Add to queue>>"),
        )
        // A5 shuffle — Shuffle is not irreversible, survives.
        assertEquals(4, plan("shuffle my liked songs", "<<OPEN|Spotify>> <<TAP|Your Library>> <<TAP|Liked Songs>> <<TAP|Shuffle>>").size)
        // A6 pause / A7 skip — single in-app taps.
        assertEquals(listOf(ScreenStep.Tap("Pause")), plan("pause the music", "<<TAP|Pause>>"))
        assertEquals(listOf(ScreenStep.Tap("Next")), plan("skip this song", "<<TAP|Next>>"))
        // A8 add to a playlist — "add to playlist" is not irreversible.
        assertEquals(3, plan("add this song to my chill playlist", "<<TAP|More options>> <<TAP|Add to playlist>> <<PICK|my chill playlist>>").size)
        // A9 next podcast episode.
        assertEquals(4, plan("play the next episode of my podcast", "<<OPEN|Spotify>> <<TAP|Your Library>> <<TAP|Podcasts>> <<PICK|the next unplayed episode>>").size)
        // A10 repeat.
        assertEquals(listOf(ScreenStep.Tap("Repeat")), plan("turn on repeat for this album", "<<TAP|Repeat>>"))
    }

    // ============================ B · Shopping (12) — SpendGuard ============================

    @Test
    fun shopping_scenarios() {
        val orderReply = "<<OPEN|Blinkit>> <<TAP|Search>> <<TYPE|milk>> <<ENTER>> <<PICK|milk>> <<TAP|Add>> " +
            "<<TAP|Search>> <<TYPE|bread>> <<ENTER>> <<PICK|bread>> <<TAP|Add>> <<TAP|Checkout>>"
        // B1 the model over-reaches to Checkout; it is withheld, the two Adds survive.
        val b1 = plan("order milk and bread on blinkit", orderReply)
        assertFalse("B1 checkout withheld", b1.contains(ScreenStep.Tap("Checkout")))
        assertEquals("B1 both adds kept", 2, b1.count { it == ScreenStep.Tap("Add") })
        // B2 add to cart, no checkout emitted — everything survives.
        assertEquals(6, plan("add 2 packs of chips to my zepto cart", "<<OPEN|Zepto>> <<TAP|Search>> <<TYPE|chips>> <<ENTER>> <<PICK|chips>> <<TAP|Add>>").size)
        // B3 "buy" authorises the purchase.
        assertTrue("B3 buy authorises", plan("buy dog food on amazon", "<<OPEN|Amazon>> <<TAP|Search>> <<TYPE|dog food>> <<ENTER>> <<PICK|dog food>> <<TAP|Add to Cart>> <<TAP|Buy Now>>").contains(ScreenStep.Tap("Buy Now")))
        // B4 explicit checkout.
        assertTrue("B4 checkout authorised", plan("checkout my blinkit cart", "<<OPEN|Blinkit>> <<TAP|Cart>> <<TAP|Checkout>>").contains(ScreenStep.Tap("Checkout")))
        // B5 NEGATION: "don't check out" must withhold the checkout (fix target).
        assertFalse("B5 negated checkout withheld", plan("add apples but dont check out on zepto", "<<OPEN|Zepto>> <<TAP|Search>> <<TYPE|apples>> <<ENTER>> <<PICK|apples>> <<TAP|Add>> <<TAP|Checkout>>").contains(ScreenStep.Tap("Checkout")))
        // B6 place order / B7 pay — authorised.
        assertEquals(listOf(ScreenStep.Tap("Place Order")), plan("place the order", "<<TAP|Place Order>>"))
        assertEquals(listOf(ScreenStep.Tap("Pay")), plan("pay for my order now", "<<TAP|Pay>>"))
        // B8 three adds, no checkout.
        assertEquals(3, plan("add milk, eggs, and butter to blinkit", "<<OPEN|Blinkit>> <<TAP|Add>> <<TAP|Add>> <<TAP|Add>>").count { it == ScreenStep.Tap("Add") })
        // B9 "Reorder" is one word — not the irreversible "order".
        assertEquals(listOf(ScreenStep.Open("Blinkit"), ScreenStep.Tap("Reorder")), plan("reorder my usual groceries on blinkit", "<<OPEN|Blinkit>> <<TAP|Reorder>>"))
        // B10 browsing only.
        assertEquals(4, plan("search for organic honey on blinkit", "<<OPEN|Blinkit>> <<TAP|Search>> <<TYPE|organic honey>> <<ENTER>>").size)
        // B11 pick then add.
        assertEquals(listOf(ScreenStep.Pick("the cheapest option"), ScreenStep.Tap("Add")), plan("add the cheapest option to cart", "<<PICK|the cheapest option>> <<TAP|Add>>"))
        // B12 "empty" authorises the Remove tap.
        assertTrue("B12 empty authorises remove", plan("empty my blinkit cart", "<<OPEN|Blinkit>> <<TAP|Cart>> <<TAP|Remove all>>").contains(ScreenStep.Tap("Remove all")))
    }

    // ============================ C · Messaging (8) — SendGuard ============================

    @Test
    fun messaging_scenarios() {
        // C1 "type …" drops the trailing submit.
        assertEquals(
            listOf(ScreenStep.Open("WhatsApp"), ScreenStep.Tap("Mom"), ScreenStep.Type("good morning")),
            plan("type good morning in mom's chat", "<<OPEN|WhatsApp>> <<TAP|Mom>> <<TYPE|good morning>> <<ENTER>>"),
        )
        // C2 explicit send keeps (and is NOT stripped by SpendGuard — the messaging regression).
        assertEquals(
            listOf(ScreenStep.Open("WhatsApp"), ScreenStep.Tap("Dad"), ScreenStep.Type("I'll be late"), ScreenStep.Tap("Send")),
            plan("send dad a message that I'll be late", "<<OPEN|WhatsApp>> <<TAP|Dad>> <<TYPE|I'll be late>> <<TAP|Send>>"),
        )
        // C3 draft — nothing to send in the plan, survives.
        assertEquals(2, plan("draft a reply to my boss", "<<TAP|Message>> <<TYPE|Sounds good>>").size)
        // C4 "reply saying yes" is a send, not compose — Send kept.
        assertTrue(plan("reply saying yes", "<<TAP|Reply>> <<TYPE|yes>> <<TAP|Send>>").contains(ScreenStep.Tap("Send")))
        // C5 NEGATION: "don't send it yet" drops the Send (fix target).
        assertEquals(
            listOf(ScreenStep.Tap("Message"), ScreenStep.Type("see you soon")),
            plan("write to the group but dont send it yet", "<<TAP|Message>> <<TYPE|see you soon>> <<TAP|Send>>"),
        )
        // C6 post.
        assertTrue(plan("post this to my story", "<<OPEN|Instagram>> <<TAP|Your Story>> <<TAP|Post>>").contains(ScreenStep.Tap("Post")))
        // C7 compose an email — no send in the plan.
        assertEquals(3, plan("compose an email to HR", "<<OPEN|Gmail>> <<TAP|Compose>> <<TYPE|Please find attached>>").size)
        // C8 "text …" is a send intent.
        assertTrue(plan("text mom happy birthday", "<<OPEN|Messages>> <<TAP|Mom>> <<TYPE|Happy birthday>> <<TAP|Send>>").contains(ScreenStep.Tap("Send")))
    }

    // ==================== D · Navigation / app-lock / routing (10) — AgentLoop ====================

    @Test
    fun navigation_and_routing_scenarios() {
        // D1 open + interact → errand (driven step-by-step).
        assertTrue("D1 is an errand", AgentLoop.isErrand(steps("<<OPEN|YouTube>> <<TAP|Search>> <<TYPE|jazz>> <<ENTER>>")))
        // D2 a single open → batch.
        assertFalse("D2 single open is not an errand", AgentLoop.isErrand(steps("<<OPEN|Settings>>")))
        // D3 back / D4 home.
        assertEquals(listOf(ScreenStep.Back), plan("go back", "<<BACK>>"))
        assertEquals(listOf(ScreenStep.Home), plan("go to the home screen", "<<HOME>>"))
        // D5 app-lock stops an errand wandering to another app.
        val d5 = driveErrand("Dominos", listOf("<<OPEN|Dominos>>", "<<TAP|Menu>>", "Calling them instead. <<OPEN|Phone>>"))
        assertEquals("blocked:${AgentLoop.LEFT_APP}", d5)
        // D6 a chained multi-app request is app-locked after the first app.
        val d6 = driveErrand("WhatsApp", listOf("<<OPEN|WhatsApp>>", "<<TAP|Mom>>", "<<OPEN|Instagram>>"))
        assertEquals("blocked:${AgentLoop.LEFT_APP}", d6)
        // D7 re-opening the same app to refine is allowed.
        assertTrue(AgentLoop.parseMove("<<OPEN|Amazon>>", stayInApp = "Amazon Music") is AgentMove.Act)
        // D8 a repeated no-op tap breaks the loop.
        assertEquals("blocked:${AgentLoop.GOING_IN_CIRCLES}", driveErrand("Blinkit", listOf("<<OPEN|Blinkit>>", "<<TAP|X>>", "<<TAP|X>>")))
        // D9 the step budget stops an endless loop.
        assertEquals("exhausted", driveErrand(null, ('A'..'I').map { "<<TAP|$it>>" }))
        // D10 an in-app follow-up (no open) → batch.
        assertFalse("D10 in-app follow-up is not an errand", AgentLoop.isErrand(steps("<<TAP|Mom>>")))
        // D11 device trace: "opened Facebook" then <<BACK>> backed straight out of the
        // app. Right after the Open there is nothing to go back from — refuse it.
        assertEquals("blocked:${AgentLoop.JUST_ARRIVED}", driveErrand("Facebook", listOf("<<OPEN|Facebook>>", "<<BACK>>")))
        // D12 same failure again in Zomato — the guard is app-agnostic.
        assertEquals("blocked:${AgentLoop.JUST_ARRIVED}", driveErrand("Zomato", listOf("<<OPEN|Zomato>>", "<<HOME>>")))
    }

    // ==================== E · Safety / ask-or-act / irreversible (10) ====================

    @Test
    fun safety_scenarios() {
        // E1 asking + acting → every step dropped.
        assertEquals(emptyList<ScreenStep>(), plan("play some music", "Which app would you like me to use?\n<<OPEN|YouTube>>"))
        // E2 an explicitly-requested delete survives the guard (user named it).
        assertTrue(plan("delete all my photos", "<<OPEN|Photos>> <<TAP|Select all>> <<TAP|Delete>>").contains(ScreenStep.Tap("Delete")))
        // E3 "call mom" — the Call tap is authorised (not stripped as if unrequested).
        assertTrue(plan("call mom", "<<OPEN|Phone>> <<PICK|Mom in the contact list>> <<TAP|Call>>").contains(ScreenStep.Tap("Call")))
        // E4 explicit share survives.
        assertTrue(plan("share this photo with dad", "<<TAP|Share>> <<PICK|Dad>> <<TAP|Send>>").contains(ScreenStep.Tap("Share")))
        // E5 in the driven loop, an irreversible tap asks first instead of acting.
        assertTrue(AgentLoop.parseMove("<<TAP|Place order>>") is AgentMove.Ask)
        // E6 a genuine alarm is kept; E7 a phantom one is dropped; E8 a negated one is dropped.
        assertEquals(1, alarms("set an alarm for 7:30 for the gym", "<<ALARM|SET|07:30|Gym>>").size)
        assertEquals(emptyList<AlarmAction>(), alarms("play Beat It", "<<ALARM|TIMER|600|nap>>"))
        assertEquals(emptyList<AlarmAction>(), alarms("dont set an alarm", "<<ALARM|TIMER|600|nap>>"))
        // E9 a dead-end errand reply is a speakable Blocked, not a crash.
        assertTrue(AgentLoop.parseMove("I can't see a cart on this screen.") is AgentMove.Blocked)
        // E10 an on-screen one-time code is masked before it leaves the device.
        assertEquals("your code is ***", ScreenMatch.redactSensitive("your code is 481922", isPassword = false))
        // E11 device trace: "set an alarm for tomorrow" → "What time?" → "6 o'clock".
        // The confirming turn has no alarm word; with the question as context it is kept.
        assertEquals(
            1,
            alarms("6 o'clock", "What time would you like the alarm to go off tomorrow?", "<<ALARM|SET|06:00|Wake Up>>").size,
        )
        // …but the same "6 o'clock" with no pending question sets nothing.
        assertEquals(emptyList<AlarmAction>(), alarms("6 o'clock", "<<ALARM|SET|06:00|Wake Up>>"))
    }

    // --- errand driver (success path), copied from ScreenControlScenarioTest ---
    private fun driveErrand(stayInApp: String?, replies: List<String>): String {
        val taken = mutableListOf<ScreenStep>()
        for (reply in replies) {
            if (AgentLoop.exhausted(taken.size)) return "exhausted"
            when (val move = AgentLoop.parseMove(reply, taken = taken, stayInApp = stayInApp)) {
                is AgentMove.Act -> taken.add(move.step)
                is AgentMove.Blocked -> return "blocked:${move.reason}"
                is AgentMove.Ask -> return "ask"
                AgentMove.Done -> return "done"
            }
        }
        return "ran-out"
    }
}
