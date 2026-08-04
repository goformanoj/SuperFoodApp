package com.jarvis.os.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.jarvis.os.ai.Brain
import com.jarvis.os.ai.agentStep
import com.jarvis.os.ai.AGENT_PROMPT
import com.jarvis.os.ai.ModelRouter
import com.jarvis.os.ai.Tier
import com.jarvis.os.alarm.AlarmActions
import com.jarvis.os.alarm.AlarmSetter
import com.jarvis.os.alarm.AlarmVolume
import com.jarvis.os.calendar.CalAction
import com.jarvis.os.calendar.CalendarActions
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.calendar.CalendarWriter
import com.jarvis.os.control.AppLauncher
import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenControlService
import com.jarvis.os.control.ScreenStep
import com.jarvis.os.control.WorkSessionService
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.data.ConversationStore
import com.jarvis.os.data.MemoryAction
import com.jarvis.os.data.MemoryActions
import com.jarvis.os.data.UserPreferences
import com.jarvis.os.data.formatMemory
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.files.ArtifactActions
import com.jarvis.os.files.ArtifactStore
import com.jarvis.os.files.ArtifactWriter
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.Speaker
import com.jarvis.os.voice.VoiceController
import com.jarvis.os.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates the assistant loop:
 *   listen (SpeechRecognizer) -> think (Groq) -> speak (TextToSpeech) -> listen…
 *
 * Deliberately simple and always-on: while the screen is visible it just listens,
 * answers, and listens again — no wake word, no "are you there" hand-off. It
 * keeps a persisted conversation for context and can act on the calendar and the
 * screen. Owns the single [VoiceUiState] the UI observes; driven from the
 * Activity lifecycle (onMicPermission / resume / pause / destroy).
 *
 * It also owns the process's only [VoiceController]. Listening starts and stops
 * in exactly one place, [applyMicOwner], which obeys [WorkSession] — that is how
 * "one microphone owner, always" is kept true once work sessions let JARVIS
 * carry on listening from the background.
 */
class AssistantEngine(context: Context) {

    private val appContext = context.applicationContext
    private val store = ConversationStore(appContext)
    private val userPrefs = UserPreferences(appContext)
    private val artifacts = ArtifactStore(appContext)
    private val playbook = PlaybookStore(appContext)
    /** What the user asked for, kept so a sequence that works can be remembered. */
    private var pendingGoal: String = ""
    /** Everything done for the current goal — planned and agent — for the history line. */
    private var stepsTaken: List<ScreenStep> = emptyList()

    /**
     * How many steps the AGENT has chosen for the current goal.
     *
     * Separate from [stepsTaken] on purpose. The budget is about how long the
     * loop may keep guessing, so counting the original plan against it spends
     * the allowance before the loop starts.
     */
    private var agentSteps: Int = 0
    private val conversation: MutableList<ChatTurn> = store.load()

    private val _state = mutableStateOf(VoiceUiState(status = "Starting…", messages = conversation.toList()))
    val state: State<VoiceUiState> get() = _state

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val voice = VoiceController(appContext)
    private val speaker = Speaker(appContext)

    // Decides who may hold the microphone. Every listen/stop decision below goes
    // through this one object, so a second listener cannot come into existence.
    private val session = WorkSession()

    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var micGranted = false
    private var mediaPlaying = false
    private var busy = false // thinking or speaking — do not listen
    // A screen action (open app / tap) to run AFTER JARVIS finishes speaking, so
    // the spoken reply isn't cut off when the screen switches away.
    private var pendingScreen: ScreenActions.Plan? = null

    // What the user actually asked for, so a failed step can be re-planned
    // against the goal rather than against the step that failed.
    private var currentGoal: String = ""

    init {
        voice.onReady = { if (!busy) set { it.copy(orb = OrbState.Listening, status = "Listening…") } }
        voice.onPartial = { text ->
            if (!busy) set { it.copy(orb = OrbState.Listening, status = "Listening…", transcript = text) }
        }
        voice.onAmplitude = { amp -> if (!busy) set { it.copy(amplitude = amp) } }
        voice.onNoInput = { restartSoon() }
        voice.onFatal = { msg -> set { it.copy(orb = OrbState.Error, status = msg, amplitude = 0f) } }
        voice.onFinal = { text -> ask(text) }
        speaker.onDone = { onSpokenDone() }
        // The executor asks the model which on-screen option a <<PICK>> means. It
        // lives here because the engine owns the AI clients; the service only
        // knows what is on screen.
        ScreenControlService.onPick = { description, options, reply ->
            scope.launch {
                val choice = try {
                    Brain.choose(description, options)
                } catch (e: Exception) {
                    DebugLog.log(DebugLog.Stage.ERROR, "pick failed: ${e.message ?: e.javaClass.simpleName}")
                    null
                }
                reply(choice)
            }
        }
        // A step failed, which means the screen is not what the plan assumed.
        // Rather than re-planning the rest blind — which is how the original
        // sequence got it wrong in the first place — hand over to the agent
        // loop: decide ONE action from what is actually on screen, run it, look
        // again. A failure stops being a special case and becomes the next
        // observation.
        ScreenControlService.onRecover = { failedStep, reason, screen, reply ->
            scope.launch {
                val move = try {
                    val answer = Brain.generate(
                        messages = listOf(
                            ChatTurn(
                                ChatTurn.USER,
                                agentStep(
                                    goal = currentGoal,
                                    done = AgentLoop.historyOf(stepsTaken) +
                                        "; then $failedStep FAILED ($reason)",
                                    screen = screen,
                                ),
                            ),
                        ),
                        context = "",
                        systemOverride = AGENT_PROMPT,
                        tier = Tier.SMART,
                    )
                    AgentLoop.parseMove(answer, avoid = failedStep)
                } catch (e: Exception) {
                    DebugLog.log(DebugLog.Stage.ERROR, "agent step failed: ${e.message ?: e.javaClass.simpleName}")
                    AgentMove.Blocked(e.message ?: "no answer")
                }

                when (move) {
                    is AgentMove.Act -> {
                        // Counts what the AGENT has done, not what was planned.
                        // Seeding this from the plan spent the whole budget
                        // before the loop began: a trace shows a nine-step plan
                        // fail at step two and the very first agent move logged
                        // as "step 10/10", leaving no room to recover at all.
                        if (AgentLoop.exhausted(agentSteps)) {
                            // Stop and say where it got to. A loop that quietly
                            // gives up looks identical to one still working.
                            DebugLog.log(DebugLog.Stage.SCREEN, "agent budget spent")
                            reply(emptyList())
                            main.post { say(AgentLoop.exhaustedMessage(currentGoal)) }
                            return@launch
                        }
                        agentSteps += 1
                        stepsTaken = stepsTaken + move.step
                        DebugLog.log(
                            DebugLog.Stage.SCREEN,
                            "agent step $agentSteps/${AgentLoop.MAX_STEPS}: ${move.step}",
                        )
                        // One step, not a plan. The service calls back here again
                        // if it fails, which is the loop.
                        reply(listOf(move.step))
                    }
                    AgentMove.Done -> {
                        DebugLog.log(DebugLog.Stage.SCREEN, "agent says the goal is met")
                        reply(emptyList())
                    }
                    is AgentMove.Ask -> {
                        // The loop acts without asking between steps, so anything
                        // irreversible stops here and the user decides.
                        DebugLog.log(DebugLog.Stage.SCREEN, "agent stopped to ask: ${move.question}")
                        reply(emptyList())
                        main.post { say(move.question) }
                    }
                    is AgentMove.Blocked -> {
                        // Say so. Logging alone leaves the user listening to
                        // silence that looks exactly like work still in
                        // progress — the same failure the step budget reports.
                        DebugLog.log(DebugLog.Stage.SCREEN, "agent is stuck: ${move.reason}")
                        reply(emptyList())
                        main.post { say(AgentLoop.blockedMessage(currentGoal, move.reason)) }
                    }
                }
            }
        }
        // Tapping Talk while audio plays claims the mic for one turn.
        WorkSessionService.onTalkRequested = {
            main.post {
                session.requestTalk()
                DebugLog.log(DebugLog.Stage.SESSION, "Talk tapped — listening for one turn")
                applyMicOwner()
            }
        }
        // The notification's Stop action ends the session from outside the app.
        WorkSessionService.onStopRequested = {
            main.post {
                session.end()
                DebugLog.log(DebugLog.Stage.SESSION, "ended by notification Stop")
                applyMicOwner()
            }
        }
    }

    fun onMicPermission(granted: Boolean) {
        micGranted = granted
        session.onMicPermission(granted)
        if (granted) {
            applyMicOwner()
        } else {
            set { it.copy(orb = OrbState.Error, status = "Microphone permission needed", amplitude = 0f) }
        }
    }

    fun resume() {
        session.onVisibilityChanged(true)
        // Coming back to the foreground (e.g. after being sent to Settings) — any
        // interrupted think/speak is abandoned, so clear busy and start listening
        // again. Without this the "busy" flag could stay stuck and it never
        // resumes hearing you.
        busy = false
        applyMicOwner()
    }

    fun pause() {
        session.onVisibilityChanged(false)
        main.removeCallbacksAndMessages(null)
        // Outside a session, leaving the screen silences JARVIS as before. Inside
        // one, it keeps talking and listening from the background.
        if (!session.isActive) speaker.stop()
        applyMicOwner()
        set { it.copy(amplitude = 0f) }
    }

    /**
     * The single place that starts or stops listening. It asks [session] who owns
     * the microphone and obeys — there is no other path to `voice.startListening`
     * during normal operation, which is what keeps "exactly one owner" true.
     */
    private fun applyMicOwner() {
        // Track the service regardless of [busy], so the process is already
        // foreground-legal by the time we want to listen again.
        if (session.needsForegroundService) {
            WorkSessionService.start(appContext, listening = !session.yieldedToMedia)
        } else {
            WorkSessionService.stop(appContext)
        }

        // While a session is up, keep watching whether audio is playing, so
        // listening resumes on its own when the song ends.
        if (session.isActive) scheduleMediaCheck()

        if (busy) return // mid think/speak — onSpokenDone re-applies this

        when (session.owner) {
            MicOwner.NONE -> {
                voice.stopListening()
                set {
                    it.copy(
                        orb = if (micGranted) OrbState.Idle else OrbState.Error,
                        status = when {
                            !micGranted -> "Microphone permission needed"
                            session.yieldedToMedia -> "Paused so your audio can play"
                            else -> "Paused"
                        },
                        amplitude = 0f,
                    )
                }
            }
            MicOwner.ENGINE, MicOwner.SESSION -> listen()
        }
    }

    /**
     * Watches whether something is playing through the speaker.
     *
     * Holding the microphone takes audio focus, which pauses playback exactly
     * like an incoming call — so after JARVIS starts a song, continuing to listen
     * would undo the very thing it was asked to do. It steps back while audio
     * plays and picks up again by itself once it stops.
     */
    private fun scheduleMediaCheck() {
        main.removeCallbacks(mediaCheck)
        main.postDelayed(mediaCheck, MEDIA_POLL_MS)
    }

    private val mediaCheck = object : Runnable {
        override fun run() {
            if (!session.isActive) return
            // Our own speech comes out of the music stream, so ignore it — the
            // check would otherwise see JARVIS talking and stand down forever.
            val playing = !busy && audio.isMusicActive
            if (playing != mediaPlaying) {
                mediaPlaying = playing
                session.onMediaPlaying(playing)
                DebugLog.log(
                    DebugLog.Stage.SESSION,
                    if (playing) "audio started — pausing listening so it can play" else "audio stopped — listening again",
                )
                applyMicOwner()
            }
            main.postDelayed(this, MEDIA_POLL_MS)
        }
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        session.end()
        ScreenControlService.onPick = null
        ScreenControlService.onRecover = null
        main.removeCallbacks(mediaCheck)
        WorkSessionService.onStopRequested = null
        WorkSessionService.onTalkRequested = null
        WorkSessionService.stop(appContext)
        voice.destroy()
        speaker.shutdown()
        scope.cancel()
    }

    fun clearConversation() {
        conversation.clear()
        store.clear()
        set { it.copy(messages = emptyList(), transcript = "", reply = "") }
    }

    /**
     * Applies what JARVIS decided to remember about the user. Kept separate from
     * the typed instructions so the screen can show — and delete — exactly what
     * was learned automatically.
     */
    private fun applyMemories(memories: List<MemoryAction>) {
        memories.forEach { memory ->
            when (memory) {
                is MemoryAction.Remember ->
                    if (userPrefs.remember(memory.fact)) {
                        DebugLog.log(DebugLog.Stage.THINK, "remembered: ${memory.fact}")
                    }
                is MemoryAction.Forget -> {
                    val gone = userPrefs.forget(memory.about)
                    if (gone > 0) DebugLog.log(DebugLog.Stage.THINK, "forgot $gone about \"${memory.about}\"")
                }
            }
        }
    }

    fun learnedFacts(): List<String> = userPrefs.learnedFacts()

    fun forgetFact(fact: String) {
        userPrefs.forget(fact)
    }

    // --- user preferences, surfaced to the settings screens -------------------

    fun customInstructions(): String = userPrefs.customInstructions

    fun saveCustomInstructions(text: String) {
        userPrefs.customInstructions = text
        DebugLog.log(DebugLog.Stage.THINK, "custom instructions updated (${text.trim().length} chars)")
    }

    fun themeId(): String = userPrefs.themeId

    fun saveThemeId(id: String) {
        userPrefs.themeId = id
    }

    // --- voice settings, surfaced to the Speech screen -----------------------

    /** Voices this device can use, best first. */
    fun voiceOptions(): List<com.jarvis.os.voice.Speaker.Option> = speaker.options()

    fun currentVoiceId(): String? = speaker.currentVoiceId()

    /** True when the installed speech data is poor enough to be worth upgrading. */
    fun shouldOfferVoiceDownload(): Boolean = speaker.shouldOfferBetterVoices()

    fun markVoiceDownloadOffered() = speaker.markVoiceDownloadOffered()

    fun chooseVoice(id: String) {
        speaker.useVoice(id)
        DebugLog.log(DebugLog.Stage.SPOKE, "voice changed to $id")
    }

    /** Audition the current voice without disturbing the conversation loop. */
    fun previewVoice(text: String) = speaker.preview(text)

    /**
     * Runs a typed command through the exact same pipeline as a spoken one
     * (brain -> markers -> calendar/screen actions), skipping only the
     * microphone. This is how the app is tested without speech: see the
     * Diagnostics screen.
     */
    fun submitText(text: String) {
        if (text.isBlank()) return
        ask(text, source = "typed")
    }

    private fun listen() {
        if (!voice.isAvailable()) {
            set { it.copy(orb = OrbState.Error, status = "Speech recognition unavailable") }
            return
        }
        busy = false
        set { it.copy(orb = OrbState.Listening, status = "Listening…", amplitude = 0f) }
        voice.startListening()
    }

    private fun restartSoon() {
        if (busy || session.owner == MicOwner.NONE) return
        main.postDelayed({ if (!busy && session.owner != MicOwner.NONE) voice.startListening() }, RESTART_MS)
    }

    private fun ask(userText: String, source: String = "voice") {
        busy = true
        pendingScreen = null
        voice.stopListening()
        main.removeCallbacksAndMessages(null)

        DebugLog.log(DebugLog.Stage.HEARD, "($source) $userText")
        currentGoal = userText

        // "Thank you Jarvis" ends the work session. Handled here, before the model
        // is called, so it always works even if the network is down.
        if (session.endIfStopPhrase(userText)) {
            DebugLog.log(DebugLog.Stage.SESSION, "ended by stop phrase")
            val farewell = "Anytime."
            addTurn(ChatTurn(ChatTurn.USER, userText))
            addTurn(ChatTurn(ChatTurn.ASSISTANT, farewell))
            DebugLog.log(DebugLog.Stage.SPOKE, farewell)
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", transcript = userText, reply = farewell) }
            speaker.speak(farewell)
            return
        }

        addTurn(ChatTurn(ChatTurn.USER, userText))
        set {
            it.copy(orb = OrbState.Thinking, status = "Thinking…", transcript = userText, reply = "", amplitude = 0f)
        }

        if (!Brain.hasKey()) {
            val msg = "No AI key yet. Add a GROQ_API_KEY secret in GitHub to switch on my brain."
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = msg) }
            speaker.speak(msg)
            return
        }

        // A route that already worked for this request beats deriving one again:
        // it is instant, it costs no tokens against Groq's per-minute limit, and
        // it is the sequence that demonstrably succeeded rather than a fresh
        // guess. Only sequences that ran clean are ever stored, and never any
        // that do something irreversible.
        val known = playbook.find(userText)
        if (known != null && ScreenControlService.isRunning()) {
            val (route, steps) = known
            playbook.markUsed(route.template, System.currentTimeMillis())
            DebugLog.log(
                DebugLog.Stage.THINK,
                "replaying known route \"${route.template}\" (used ${route.uses + 1}x)",
            )
            DebugLog.log(DebugLog.Stage.MARKERS, "playbook: ${Playbook.markersOf(steps)}")
            val plan = ScreenActions.Plan(clean = "On it.", steps = steps)
            pendingGoal = userText
            pendingScreen = plan
            addTurn(ChatTurn(ChatTurn.ASSISTANT, plan.clean))
            DebugLog.log(DebugLog.Stage.SPOKE, plan.clean)
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = plan.clean) }
            speaker.speak(plan.clean)
            return
        }

        val history = conversation.takeLast(MAX_CONTEXT_TURNS)
        scope.launch {
            try {
                // Commands go to the small model; anything that needs thinking
                // goes to the big one. Groq's quotas are per model, so this keeps
                // the 70b allowance for the turns that actually need it.
                val tier = ModelRouter.tierFor(userText)
                val prompt = buildContext()
                val raw = Brain.generate(history, prompt, tier)
                DebugLog.log(DebugLog.Stage.REPLY, "${Brain.providerName()}/${tier.name}: $raw")

                // Strip any leftover end-marker the model may add; we simply keep listening.
                val reply = raw.replace("<<END>>", "", ignoreCase = true)
                // Pull out and run the two command families (calendar + screen).
                val (afterCal, actions) = CalendarActions.parse(reply)
                val (afterAlarm, rawAlarms) = AlarmActions.parse(afterCal)
                // An alarm nobody asked for is a real noise at a real time, and
                // the user finds out when it goes off. A trace had "play Beat It"
                // set a ten-minute timer called "nap".
                val alarms = AlarmGuard.apply(userText, rawAlarms)
                if (alarms.size != rawAlarms.size) {
                    DebugLog.log(
                        DebugLog.Stage.MARKERS,
                        "alarm suppressed: nothing in \"$userText\" asked for one — " +
                            "dropped ${rawAlarms.size - alarms.size}",
                    )
                }
                val (afterMemory, memories) = MemoryActions.parse(afterAlarm)
                applyMemories(memories)
                val (afterFiles, fileRequests) = ArtifactActions.parse(afterMemory)
                val filesMade = withContext(Dispatchers.IO) {
                    fileRequests.count { ArtifactWriter.write(appContext, it, artifacts) != null }
                }
                val parsed = ScreenActions.parse(afterFiles)
                // "Type it" and "send it" are different instructions and only one
                // is reversible. The model's search examples all end in a submit,
                // so it tends to append one — drop it when the user asked only to
                // compose.
                // Asking and acting are different turns. A trace asked "what app
                // would you like to use?" and opened YouTube in the same reply.
                val asked = AskGuard.apply(parsed.clean, parsed.steps)
                if (asked.size != parsed.steps.size) {
                    DebugLog.log(
                        DebugLog.Stage.MARKERS,
                        "actions suppressed: the reply asks the user a question — " +
                            "waiting for the answer instead of deciding for them",
                    )
                }
                val guarded = SendGuard.apply(userText, asked)
                if (guarded.size != asked.size) {
                    DebugLog.log(
                        DebugLog.Stage.MARKERS,
                        "send suppressed: user asked to compose, not send — dropped " +
                            "${asked.drop(guarded.size).joinToString(", ")}",
                    )
                }
                // Nobody asked to check out. A trace of "add some bread" produced
                // a plan ending in Tap(Checkout), and only a typing failure
                // earlier in the sequence stopped it running.
                val spendStop = SpendGuard.stopsAt(userText, guarded)
                val safe = SpendGuard.apply(userText, guarded)
                if (spendStop != null) {
                    DebugLog.log(
                        DebugLog.Stage.MARKERS,
                        "spend suppressed: user did not ask to $spendStop — dropped " +
                            "${guarded.drop(safe.size).joinToString(", ")}",
                    )
                }
                val spendNote = spendStop?.let { SpendGuard.explain(it) }
                val plan = parsed.copy(steps = safe)
                val clean = plan.clean
                DebugLog.log(
                    DebugLog.Stage.MARKERS,
                    "calendar=${actions.size} screen=${plan.steps.joinToString(", ").ifEmpty { "none" }}",
                )
                val (added, deleted) = withContext(Dispatchers.IO) {
                    var a = 0
                    var d = 0
                    for (action in actions) {
                        when (action) {
                            is CalAction.Add ->
                                if (CalendarWriter.addEvent(appContext, action.title, action.startMillis, action.durationMin)) a++
                            is CalAction.Delete ->
                                d += CalendarWriter.deleteEvents(appContext, action.title, action.date, action.time)
                        }
                    }
                    a to d
                }
                if (actions.isNotEmpty()) {
                    DebugLog.log(DebugLog.Stage.CALENDAR, "added=$added deleted=$deleted")
                }
                val alarmsSet = withContext(Dispatchers.IO) {
                    alarms.count { AlarmSetter.set(appContext, it) }
                }
                // Setting an alarm and having it ring are different things: the
                // alarm stream has its own volume and a silenced phone accepts the
                // alarm without complaint, then says nothing at the hour.
                if (AlarmVolume.asksToRaise(userText)) raiseAlarmVolume()
                val volumeNote = if (alarmsSet > 0) alarmVolumeWarning() else null
                // Decide what to SAY now, but defer any app switch until after we
                // finish speaking (run in onSpokenDone) so the reply isn't cut off.
                val needsPerm = plan.needsAccessibility && !ScreenControlService.isRunning()
                val spoken = when {
                    needsPerm ->
                        "To control the screen, open Accessibility settings, go to Downloaded apps, and switch on JARVIS Screen Control, then ask me again."
                    clean.isNotBlank() -> listOfNotNull(clean, spendNote, volumeNote).joinToString(" ")
                    filesMade > 0 && clean.isBlank() -> "Done — it's in your Files."
                    alarmsSet > 0 && clean.isBlank() ->
                        listOfNotNull("Done, that's set.", volumeNote).joinToString(" ")
                    added > 0 && deleted > 0 -> "Done, I've rescheduled it."
                    added > 0 -> "Okay, I've added it to your calendar."
                    deleted > 0 -> "Done, I've removed it from your calendar."
                    plan.hasAction -> listOfNotNull("On it.", spendNote).joinToString(" ")
                    spendNote != null -> spendNote
                    else -> reply
                }
                pendingScreen = if (plan.hasAction) plan else null
                if (plan.hasAction) {
                    pendingGoal = userText
                    stepsTaken = plan.steps
                    agentSteps = 0
                }
                addTurn(ChatTurn(ChatTurn.ASSISTANT, spoken))
                DebugLog.log(DebugLog.Stage.SPOKE, spoken)
                set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = spoken) }
                speaker.speak(spoken)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                DebugLog.log(DebugLog.Stage.ERROR, "Brain error: $detail")
                // The provider's full paragraph belongs in the trace, not on the
                // orb — on screen it swamps everything and reads as noise.
                val shown = if (detail.length > 120) detail.take(117) + "…" else detail
                set { it.copy(orb = OrbState.Error, status = "Brain error", reply = shown) }
                main.postDelayed({ onSpokenDone() }, 3000L)
            }
        }
    }

    private fun onSpokenDone() {
        busy = false
        // Speaking is finished — now carry out any screen action (open app / tap /
        // send to Settings). Doing it here means JARVIS completes its sentence
        // before the screen switches. If it launches an app we get backgrounded,
        // and resume() restarts listening when the user returns.
        val plan = pendingScreen
        pendingScreen = null
        if (plan != null) {
            scope.launch { withContext(Dispatchers.IO) { executeScreen(plan) } }
        }
        applyMicOwner()
    }

    private fun addTurn(turn: ChatTurn) {
        conversation.add(turn)
        while (conversation.size > MAX_STORED_TURNS) conversation.removeAt(0)
        store.save(conversation)
        set { it.copy(messages = conversation.toList()) }
    }

    private suspend fun buildContext(): String = withContext(Dispatchers.IO) {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val events = CalendarReader.upcomingEvents(appContext)
        val schedule = when {
            events == null ->
                "You do NOT have calendar access yet. If asked about the schedule, tell the " +
                    "user you need calendar permission and to grant it in the app."
            events.isEmpty() ->
                "The user's calendar has nothing scheduled in the next 7 days."
            else ->
                "Upcoming calendar events: ${events.joinToString("; ")}."
        }
        // What is actually on screen right now. Without this the model replans from
        // zero every turn — re-opening apps that are already open and re-tapping
        // things it already tapped (which is how "send the message" ended up
        // opening the contact's profile).
        val screen = ScreenControlService.instance?.describeScreen().orEmpty()
        // Note the last line: without it the model concluded from "use the labels
        // on screen" that it could ONLY act on what was visible, and started
        // refusing to open apps at all ("I can only interact with the current
        // app"). Opening never needed the screen.
        val alwaysTrue = "You can ALWAYS open any installed app with <<OPEN|Name>>, and use " +
            "<<BACK>> or <<HOME>>, no matter what is on screen — the screen list limits only what " +
            "you can TAP, never what you can open. Never tell the user you cannot open an app."

        val screenContext = if (screen.isBlank()) {
            "You cannot see the screen right now — screen control is switched off, or no app has " +
                "been opened yet. Do not pretend to know what is on screen. $alwaysTrue"
        } else {
            "$screen\nUse these REAL on-screen labels rather than guessing one. Emit ONLY the " +
                "steps still needed from here: do not re-open an app that is already the " +
                "foreground app, and do not tap a name you are already inside — in a chat, the " +
                "name at the top opens that person's profile. If the text is already typed, just " +
                "send it. $alwaysTrue"
        }

        // The user's standing instructions ride on every turn, which is why the
        // length is capped where they are entered.
        val standing = formatMemory(userPrefs.customInstructions, userPrefs.learnedFacts())

        listOf(
            "Current date/time: $now. $schedule When asked about the schedule, use ONLY this " +
                "real calendar data and do not invent events. When rescheduling or deleting, " +
                "identify the exact event from this list.",
            screenContext,
            standing,
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private enum class ScreenOutcome { NONE, DISPATCHED, NEEDS_PERMISSION }

    /**
     * Runs any screen-control actions the model asked for: opens an app and/or
     * taps a visible control. Opening an app needs no special permission; tapping
     * needs the accessibility service — if it's off, send the user to settings.
     */
    private fun executeScreen(plan: ScreenActions.Plan): ScreenOutcome {
        if (!plan.hasAction) return ScreenOutcome.NONE
        if (plan.needsAccessibility && !ScreenControlService.isRunning()) {
            DebugLog.log(DebugLog.Stage.SCREEN, "blocked: accessibility service is off")
            openAccessibilitySettings()
            return ScreenOutcome.NEEDS_PERMISSION
        }
        DebugLog.log(DebugLog.Stage.SCREEN, "running ${plan.steps.joinToString(", ")}")
        // A command that opens an app starts a work session: JARVIS keeps hearing
        // follow-ups while the user is in that app. Merely opening or closing
        // JARVIS itself never gets here, so it never starts one.
        if (plan.steps.any { it is ScreenStep.Open }) {
            session.onAppOpenedByCommand()
            DebugLog.log(DebugLog.Stage.SESSION, "started — listening for follow-ups")
            // Start the service synchronously, here, before the launch below
            // backgrounds us — posting it to the main thread would race the app
            // switch, and starting it from the background is not allowed.
            if (session.needsForegroundService) WorkSessionService.start(appContext)
            main.post { applyMicOwner() }
        }
        if (plan.needsAccessibility && AgentLoop.isErrand(plan.steps)) {
            // An errand inside an app is not planned, it is driven. Everything
            // after the first step was guessed against a screen that did not
            // exist yet — which is why a Blinkit trace kept tapping a control
            // called "Search" in an app whose search box is labelled "Search for
            // atta, dal, coke and more". Open the app, then look before each move.
            val goal = pendingGoal
            val opens = AgentLoop.opensIn(plan.steps)
            DebugLog.log(
                DebugLog.Stage.SCREEN,
                "errand: opening, then deciding each step from the screen " +
                    "(the planned ${plan.steps.size} steps are only a suggestion)",
            )
            ScreenControlService.instance?.runSteps(opens, recover = false) { _, _ ->
                main.post { driveErrand(goal, lastFailed = null) }
            }
            return ScreenOutcome.DISPATCHED
        }
        if (plan.needsAccessibility) {
            // Run the whole ordered sequence (open -> tap -> type -> enter) via the service.
            val goal = pendingGoal
            ScreenControlService.instance?.runSteps(plan.steps) { ok, ranClean ->
                // Only a sequence that ran clean is worth keeping. Routes that
                // needed recovery are exactly the ones whose steps were wrong.
                //
                // `ok` alone was not that test: recovery rescues a run and then
                // reports success, so a trace shows three routes learned in one
                // session — "did you are", "search box" — each stored from a
                // sequence whose typing step had just failed.
                if (ok && ranClean && goal.isNotBlank()) {
                    Playbook.learn(goal, plan.steps)?.let {
                        playbook.remember(it, System.currentTimeMillis())
                        DebugLog.log(
                            DebugLog.Stage.THINK,
                            "learned route \"${it.template}\" -> ${it.markers}",
                        )
                    }
                }
            }
        } else {
            // Opens only — no accessibility needed.
            for (step in plan.steps) {
                if (step is ScreenStep.Open) AppLauncher.launch(appContext, step.app)
            }
        }
        return ScreenOutcome.DISPATCHED
    }

    private fun openAccessibilitySettings() {
        try {
            appContext.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // No settings activity available — the spoken guidance still tells the user what to do.
        }
    }

    /**
     * Warns when an alarm that was just set will not actually be heard.
     *
     * The alarm stream carries its own volume, independent of media and ringer.
     * A phone turned down accepts the alarm silently and then says nothing at the
     * hour, which is the same failure as reporting a tap that did nothing.
     */
    private fun alarmVolumeWarning(): String? {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        val verdict = AlarmVolume.check(
            current = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        )
        AlarmVolume.warning(verdict)?.let {
            DebugLog.log(DebugLog.Stage.ALARM, "alarm volume check: $verdict")
            return it
        }
        return null
    }

    /** Raises the alarm stream when the user asks for it, and says what it did. */
    private fun raiseAlarmVolume() {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = AlarmVolume.targetFor(max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            DebugLog.log(DebugLog.Stage.ALARM, "alarm volume raised to $target/$max")
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse the change. Report it rather than
            // pretending the alarm is now loud.
            DebugLog.log(DebugLog.Stage.ALARM, "could not raise alarm volume: ${e.message}")
        }
    }

    /**
     * Drives an errand one action at a time: look, decide, act, look again.
     *
     * This is the ordinary path for "open Blinkit and add some bread", not a
     * fallback. The old path planned the whole sequence before the app was even
     * open and then hoped; a trace of that shows it typing into a screen with no
     * text field, tapping a control whose real label it could not have known, and
     * doing nothing the user asked for.
     *
     * [lastFailed] is the step that just failed, if any — it is passed to the
     * parser so the model cannot answer with the same move again.
     */
    private fun driveErrand(goal: String, lastFailed: ScreenStep?) {
        val service = ScreenControlService.instance
        if (service == null || goal.isBlank()) return
        if (AgentLoop.exhausted(agentSteps)) {
            DebugLog.log(DebugLog.Stage.SCREEN, "agent budget spent")
            say(AgentLoop.exhaustedMessage(goal))
            return
        }
        val screen = service.describeScreen()
        scope.launch {
            val move = try {
                val answer = Brain.generate(
                    messages = listOf(
                        ChatTurn(
                            ChatTurn.USER,
                            agentStep(
                                goal = goal,
                                done = AgentLoop.historyOf(stepsTaken) +
                                    (lastFailed?.let { "; then $it FAILED" } ?: ""),
                                screen = screen,
                            ),
                        ),
                    ),
                    context = "",
                    systemOverride = AGENT_PROMPT,
                    tier = Tier.SMART,
                )
                AgentLoop.parseMove(answer, avoid = lastFailed)
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "agent step failed: ${e.message ?: e.javaClass.simpleName}")
                AgentMove.Blocked(e.message ?: "no answer")
            }

            main.post {
                when (move) {
                    is AgentMove.Act -> {
                        agentSteps += 1
                        stepsTaken = stepsTaken + move.step
                        DebugLog.log(
                            DebugLog.Stage.SCREEN,
                            "errand step $agentSteps/${AgentLoop.MAX_STEPS}: ${move.step}",
                        )
                        // recover = false: this loop IS the recovery, and two of
                        // them on one screen would fight.
                        service.runSteps(listOf(move.step), recover = false) { ok, _ ->
                            main.post { driveErrand(goal, lastFailed = if (ok) null else move.step) }
                        }
                    }
                    AgentMove.Done -> {
                        DebugLog.log(DebugLog.Stage.SCREEN, "errand done after $agentSteps steps")
                        say("That's done.")
                    }
                    is AgentMove.Ask -> {
                        DebugLog.log(DebugLog.Stage.SCREEN, "errand stopped to ask: ${move.question}")
                        say(move.question)
                    }
                    is AgentMove.Blocked -> {
                        DebugLog.log(DebugLog.Stage.SCREEN, "errand stuck: ${move.reason}")
                        say(AgentLoop.blockedMessage(goal, move.reason))
                    }
                }
            }
        }
    }

    /**
     * Speaks one line outside the normal think-reply cycle.
     *
     * Used when the agent loop stops mid-errand — to ask before something
     * irreversible, or to report that it ran out of steps. Those are not answers
     * to a question the user just asked, but they still have to be heard.
     */
    private fun say(line: String) {
        addTurn(ChatTurn(ChatTurn.ASSISTANT, line))
        DebugLog.log(DebugLog.Stage.SPOKE, line)
        set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = line) }
        speaker.speak(line)
    }

    private fun set(block: (VoiceUiState) -> VoiceUiState) {
        _state.value = block(_state.value)
    }

    private companion object {
        const val RESTART_MS = 400L // gap before restarting the recogniser after no input
        // Every turn here is resent on EVERY request. Groq's free tier caps
        // tokens per MINUTE (~12k), and at ~3.2k tokens per request that allowed
        // only three or four commands a minute before a 429. Ten turns is still
        // several minutes of conversation.
        const val MAX_CONTEXT_TURNS = 10 // turns sent to the AI as context
        const val MAX_STORED_TURNS = 200 // turns kept on disk
        const val MEDIA_POLL_MS = 2000L // how often to check whether audio is playing
    }
}
