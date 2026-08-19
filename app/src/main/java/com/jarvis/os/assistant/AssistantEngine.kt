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
import com.jarvis.os.control.HotwordService
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
import com.jarvis.os.voice.BargeInListener
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.Speaker
import com.jarvis.os.voice.SpokenText
import com.jarvis.os.voice.Transcript
import com.jarvis.os.voice.VoiceController
import com.jarvis.os.voice.WakeWord
import com.jarvis.os.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates the assistant loop:
 *   listen (SpeechRecognizer) -> think (Groq) -> speak (TextToSpeech) -> listen…
 *
 * It listens continuously, but stays ASLEEP until it hears the wake word: asleep
 * it ignores everything that is not "Hey JARVIS"; awake it answers, acts, and
 * listens for follow-ups, returning to sleep after a spell of silence (see the
 * [awake] flag and [WakeWord]). It keeps a persisted conversation for context and
 * can act on the calendar and the screen. Owns the single [VoiceUiState] the UI
 * observes; driven from the Activity lifecycle (onMicPermission / resume / pause /
 * destroy).
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

    /** What this errand has already tried, so the loop can refuse to repeat itself. */
    private var errandSteps: List<ScreenStep> = emptyList()

    /**
     * Identifies the errand allowed to drive the screen.
     *
     * Bumped by every new utterance. A callback holding an older token stops
     * instead of acting — otherwise an abandoned errand keeps choosing steps
     * underneath the new one, which a trace shows happening for a full minute.
     */
    private var errandToken: Int = 0

    /**
     * True while an errand is part-way through — driving the screen, or waiting
     * on the model for its next move.
     *
     * Deliberately not folded into "speaking": a task is mostly SILENCE, and the
     * silence is the part that was unreachable. See [WorkSession.onBusyWithTask].
     */
    private var taskRunning = false

    /**
     * The exact step JARVIS last asked permission for, kept until it is answered.
     *
     * Storing the STEP, not just the fact that a question was asked, is the whole
     * fix: a confirmation must run the thing that was confirmed, never a fresh
     * plan for the same goal. See [Confirmation].
     */
    private var pendingConfirm: ScreenStep? = null

    private fun setTaskRunning(value: Boolean) {
        if (taskRunning == value) return
        taskRunning = value
        session.onBusyWithTask(value)
        // The microphone owner changes with this, so it has to be re-derived —
        // the whole point is that the mic stays reachable while a task runs.
        applyMicOwner()
    }

    /**
     * Counts turns, purely so [Acknowledgement] can vary its wording.
     *
     * The complaint that produced it: every action reply was "On it.", every
     * summons was "Yes?". A counter rather than a random draw keeps
     * [Acknowledgement] pure and its tests able to pin exact strings.
     */
    private var spokenTurn: Int = 0
    private val conversation: MutableList<ChatTurn> = store.load()

    private val _state = mutableStateOf(VoiceUiState(status = "Starting…", messages = conversation.toList()))
    val state: State<VoiceUiState> get() = _state

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val voice = VoiceController(appContext)
    private val speaker = Speaker(appContext)

    // Hears "Hey Jarvis" over JARVIS's own reply. Never runs at the same time as
    // [voice] -- it is an owner in its own right (MicOwner.BARGE_IN), not a
    // second listener alongside one.
    private val bargeIn = BargeInListener(appContext)

    // Decides who may hold the microphone. Every listen/stop decision below goes
    // through this one object, so a second listener cannot come into existence.
    private val session = WorkSession()

    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var micGranted = false
    private var mediaPlaying = false

    /**
     * Where the current turn has got to, and whether it may be interrupted.
     *
     * This replaced a single `busy` boolean that meant three different things at
     * once — see [TurnState] for what each of them was and why they had to come
     * apart before JARVIS could be cut off mid-sentence.
     */
    private val turn = TurnState()

    /**
     * Handler for the end-of-speech watchdog, and **deliberately not [main]**.
     *
     * [ask] and [pause] both call `main.removeCallbacksAndMessages(null)`, which
     * would silently throw the watchdog away — leaving exactly the stuck turn it
     * exists to catch, and only in the cases where something had already gone
     * wrong enough to clear the queue.
     */
    private val guard = Handler(Looper.getMainLooper())

    // Wake-word gate. When asleep, JARVIS listens but stays silent and inert until
    // it hears "Hey JARVIS" — it will not think, speak, or act on anything else.
    // Once awake it handles commands normally and, after a spell of silence, falls
    // back to sleep. A work session (opened an app on command, listening for
    // follow-ups) counts as engaged, so mid-errand follow-ups need no wake word.
    private var awake = false
    // A screen action (open app / tap) to run AFTER JARVIS finishes speaking, so
    // the spoken reply isn't cut off when the screen switches away.
    private var pendingScreen: ScreenActions.Plan? = null

    // What the user actually asked for, so a failed step can be re-planned
    // against the goal rather than against the step that failed.
    private var currentGoal: String = ""

    /**
     * True while the pending plan came from the [Playbook] rather than the model.
     *
     * Two things follow from it, and a trace shows why both are needed. A replayed
     * route must not be LEARNED again: the trace has JARVIS replay
     * "you didnt do anything" and then immediately log
     * `learned route "you didnt do anything"`, so a bad template reinforces itself
     * every time it fires. And a replayed route must be RUN, not re-driven: routing
     * it through the errand loop would throw away the very steps that were stored
     * because they worked.
     */
    private var replayingRoute = false

    init {
        voice.onReady = { if (!turn.micGated) set { it.copy(orb = idleOrb(), status = idleStatus()) } }
        voice.onPartial = { text ->
            // Show live text only while engaged; asleep it should read as waiting
            // for the wake word, not transcribing the room.
            if (!turn.micGated) set {
                it.copy(
                    orb = idleOrb(),
                    status = idleStatus(),
                    transcript = if (isEngaged()) text else "",
                )
            }
        }
        voice.onAmplitude = { amp -> if (!turn.micGated) set { it.copy(amplitude = amp) } }
        voice.onNoInput = { restartSoon() }
        voice.onFatal = { msg -> set { it.copy(orb = OrbState.Error, status = msg, amplitude = 0f) } }
        voice.onFinal = { hypotheses ->
            val best = hypotheses.firstOrNull().orEmpty()
            if (best.isNotBlank()) onHeard(best, hypotheses)
        }
        // Every end of speech arrives here — finished, errored, cut off, or never
        // started because the device has no usable engine. [TurnState] decides
        // which of those actually ends the turn: a report for an utterance that
        // was already flushed, or for a turn the user has just interrupted, must
        // not run the work that was waiting for JARVIS to stop talking.
        // Arms the barge-in listener, through [applyMicOwner]'s ownership rules.
        // Deliberately on speech actually BEGINNING rather than on asking for it:
        // in the moment before, what the microphone would pick up is the user's
        // own command trailing off, and hearing that back would cut the reply
        // off before it had said anything.
        speaker.onSpeechStarted = { seq -> if (turn.speechStarted(seq)) applyMicOwner() }
        bargeIn.onDetected = { interrupt() }
        speaker.onSpeechEnded = { seq, interrupted ->
            if (turn.speechEnded(seq, interrupted)) {
                disarmWatchdog()
                if (interrupted) {
                    DebugLog.log(DebugLog.Stage.SPOKE, "speech cut short — carrying on from there")
                }
                onSpokenDone()
            }
        }
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
                    AgentLoop.parseMove(answer, avoid = failedStep, stayInApp = AgentLoop.appOf(stepsTaken), goal = currentGoal)
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
// The wake word fired while the user was in another app with something
        // playing. Deliberately NOT the usual "bring JARVIS to the front": they
        // are watching a video, and a session exists so they can talk without
        // leaving it. This is the same one-turn mic claim the notification's Talk
        // button makes — the difference is only that they said it instead of
        // tapping it.
        HotwordService.onDetectedInSession = {
            // The detector releases its AudioRecord as it stands down, and the
            // audio HAL does not always free the input immediately — the same
            // race that answers a too-eager recogniser with RECOGNIZER_BUSY. A
            // short deliberate gap beats not hearing the command that was the
            // whole point of waking up.
            main.postDelayed(
                {
                    session.requestTalk()
                    DebugLog.log(DebugLog.Stage.HEARD, "hotword heard in session — listening for one turn")
                    applyMicOwner()
                },
                MIC_HANDOFF_MS,
            )
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
                awake = false
                DebugLog.log(DebugLog.Stage.SESSION, "ended by notification Stop")
                applyMicOwner()
            }
        }
        // The wake-word notification's Stop turns off background listening for good
        // (until re-enabled in settings), and stops the service now.
        HotwordService.onDisableRequested = {
            main.post {
                userPrefs.backgroundWake = false
                HotwordService.stop(appContext)
                DebugLog.log(DebugLog.Stage.SESSION, "background wake word turned off")
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
        // Foreground now, so the engine owns the mic — the background hotword must
        // let go, or two listeners would fight over it (the one-owner rule).
        HotwordService.stop(appContext)
        // Coming back to the foreground (e.g. after being sent to Settings). Ask
        // the engine whether it is still talking rather than assuming either way:
        // a turn abandoned while we were gone must not leave the microphone shut
        // for good, and a reply still being spoken must not be declared finished.
        turn.reconcile(speaker.isSpeaking())
        applyMicOwner()
    }

    fun pause() {
        session.onVisibilityChanged(false)
        main.removeCallbacksAndMessages(null)
        // Outside a session, leaving the screen silences JARVIS as before. Inside
        // one, it keeps talking and listening from the background.
        if (!session.isActive) {
            speaker.stop()
            // Ends the turn here and now rather than waiting to be told. Stopping
            // TTS that is already idle produces no callback at all, so anything
            // that waited for one would keep the microphone shut indefinitely.
            turn.interrupt()
            disarmWatchdog()
        }
        applyMicOwner()
        set { it.copy(amplitude = 0f) }
    }

    /**
     * Summon JARVIS to take a command — wake, acknowledge briefly, then listen
     * through the ordinary recogniser path. Used both when the assist gesture
     * launches the app (the mic-free default) and when the background wake word
     * brought it to the front. [via] is only for the trace.
     */
    fun summon(via: String = "") {
        if (turn.micGated) return
        awake = true
        DebugLog.log(DebugLog.Stage.THINK, "summoned${if (via.isBlank()) "" else " by $via"}")
        speakAck(Acknowledgement.summoned(spokenTurn++))
    }

    /**
     * The single place that starts or stops listening. It asks [session] who owns
     * the microphone and obeys — there is no other path to `voice.startListening`
     * during normal operation, which is what keeps "exactly one owner" true.
     */
    private fun applyMicOwner() {
        // Derived from the turn rather than announced by each speak path, so the
        // session's idea of "he is talking" cannot drift from the turn's. Every
        // route that changes the phase ends up here, which is what makes one
        // assignment enough.
        session.onSpeaking(turn.phase == TurnPhase.SPEAKING)

        // Track the service regardless of the turn, so the process is already
        // foreground-legal by the time we want to listen again.
        if (session.needsForegroundService) {
            WorkSessionService.start(appContext, listening = !session.yieldedToMedia)
        } else {
            WorkSessionService.stop(appContext)
        }

        // While a session is up, keep watching whether audio is playing, so
        // listening resumes on its own when the song ends.
        if (session.isActive) scheduleMediaCheck()

        // Here rather than only in [pause]. The state the wake word now covers —
        // a session that has yielded the mic to playback — is entered and left by
        // the media check, long after pause() ran, so deciding this once on the
        // way out of the foreground would have left the gap exactly as it was.
        applyHotword()

        // The floating orb, for the SAME reason — and it is worth saying plainly
        // that this was got wrong once already, three commits after writing the
        // note above. The orb was decided in resume() and pause() only, so
        // "thank you Jarvis" ended the session from the background, no lifecycle
        // callback fired, and the orb stayed on screen after the one phrase the
        // user has for dismissing him. Anything derived from session state has to
        // be re-derived wherever session state changes, and that is here.
        applyBubble()

        val owner = session.owner
        // Mid think/speak nothing may listen — with the one exception of the
        // barge-in listener, which exists precisely to be open then.
        if (turn.micGated && owner != MicOwner.BARGE_IN) {
            bargeIn.stop()
            return // onSpokenDone re-applies this
        }

        when (owner) {
            MicOwner.BARGE_IN -> {
                // Note what is NOT here: no state change. The orb must go on
                // reading Speaking, because he is.
                voice.stopListening()
                bargeIn.start()
            }
            MicOwner.NONE -> {
                bargeIn.stop()
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
            MicOwner.ENGINE, MicOwner.SESSION -> {
                if (bargeIn.stop()) {
                    // Handing the microphone from one holder to the other is the
                    // most likely thing to fail on a real device. The AudioRecord
                    // is released, but the audio HAL does not always free the
                    // input immediately, and SpeechRecognizer answers a lost race
                    // with ERROR_RECOGNIZER_BUSY — which surfaces as JARVIS
                    // simply not hearing the thing you interrupted him to say.
                    // A deliberate short gap beats a visible stumble.
                    main.postDelayed(
                        { if (!turn.micGated && engineMayListen()) listen() },
                        MIC_HANDOFF_MS,
                    )
                } else {
                    listen()
                }
            }
        }
    }

    /**
     * Shows the floating orb exactly while JARVIS is off his own screen.
     *
     * On his own screen there is already a 280dp orb in the middle of it, and a
     * second one floating over the top is clutter. Off it, the bubble is the only
     * thing that says he is there at all.
     *
     * The tap handler is re-attached here rather than once at construction,
     * because the accessibility service may connect long AFTER the engine exists —
     * a hook set once, at the wrong moment, is a bubble that draws perfectly and
     * does nothing when pressed.
     */
    private fun applyBubble() {
        val service = ScreenControlService.instance ?: return
        // Cheap enough to run on every mic reassignment, which is what it has to
        // do — see the call site in [applyMicOwner].
        service.bubble.onTap = { main.post { onBubbleTap() } }
        // Dragging the orb onto the ✕ turns the setting off. Hiding it for now
        // and bringing it back on the next session would read as the dismissal
        // not having worked.
        service.bubble.onDismissed = {
            main.post {
                userPrefs.floatingOrb = false
                DebugLog.log(DebugLog.Stage.SESSION, "floating orb off — dismissed from the screen")
            }
        }
        service.setBubbleVisible(userPrefs.floatingOrb && session.wantsBubble)
    }

    /**
     * The floating orb was tapped. It carries the same two meanings the in-app orb
     * does — cut him off if he is talking, otherwise start listening — because it
     * IS the same orb, and a control that means different things on different
     * surfaces is two controls wearing one costume.
     */
    private fun onBubbleTap() {
        if (turn.phase == TurnPhase.SPEAKING) {
            DebugLog.log(DebugLog.Stage.HEARD, "floating orb tapped — cutting in")
            interrupt()
            return
        }
        // A live session already holds the microphone foreground service, so the
        // mic can be claimed right here and the user never leaves their app —
        // the same one-turn claim the notification's Talk button makes.
        if (session.isActive) {
            DebugLog.log(DebugLog.Stage.HEARD, "floating orb tapped — listening in place")
            awake = true
            session.requestTalk()
            applyMicOwner()
            return
        }
        // With no session there is no microphone foreground service, and Android
        // 12+ refuses to let a backgrounded app start one — so there is no way to
        // open the mic from here. Bringing JARVIS to the front is not a fallback,
        // it is the only legal route, and it is what the wake word does for the
        // same reason.
        DebugLog.log(DebugLog.Stage.HEARD, "floating orb tapped — opening JARVIS")
        try {
            appContext.startActivity(
                android.content.Intent(appContext, com.jarvis.os.MainActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    .putExtra(com.jarvis.os.MainActivity.EXTRA_WOKE_BY_HOTWORD, true),
            )
        } catch (e: Exception) {
            DebugLog.log(DebugLog.Stage.ERROR, "floating orb could not open JARVIS: ${e.message}")
        }
    }

    /**
     * Starts or stops the background wake word, from the single rule in
     * [WorkSession.wantsHotword].
     *
     * The preference gate stays here because it is the user's setting rather than
     * anything about the session, but the microphone reasoning belongs next to
     * every other microphone decision, where it is pure and tested.
     */
    private fun applyHotword() {
        if (userPrefs.backgroundWake && session.wantsHotword) {
            HotwordService.start(appContext, forSession = session.isActive)
        } else {
            HotwordService.stop(appContext)
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
            val playing = !turn.ownVoiceOnStream && audio.isMusicActive
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
        disarmWatchdog()
        session.end()
        ScreenControlService.onPick = null
        ScreenControlService.onRecover = null
        main.removeCallbacks(mediaCheck)
        WorkSessionService.onStopRequested = null
        WorkSessionService.onTalkRequested = null
        HotwordService.onDetectedInSession = null
        ScreenControlService.instance?.bubble?.onTap = null
        ScreenControlService.instance?.bubble?.onDismissed = null
        WorkSessionService.stop(appContext)
        bargeIn.close()
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

    /**
     * Opens the system screen where JARVIS can be set as the default assistant, so
     * the assist gesture launches it. Tries the most specific screen first and
     * falls back, since OEMs (ColorOS included) move it around.
     */
    fun openAssistantSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next, more general screen.
            }
        }
    }

    /** Whether the background "Hey Jarvis" wake word is switched on. */
    fun backgroundWakeEnabled(): Boolean = userPrefs.backgroundWake

    /** Whether the floating orb rides over other apps. */
    fun floatingOrbEnabled(): Boolean = userPrefs.floatingOrb

    fun setFloatingOrb(enabled: Boolean) {
        userPrefs.floatingOrb = enabled
        DebugLog.log(DebugLog.Stage.SESSION, "floating orb ${if (enabled) "on" else "off"}")
        // Turning it off must take effect now, not at the next app switch. Turning
        // it ON while the settings screen is in front deliberately does nothing
        // visible — [applyBubble] shows it when JARVIS leaves the foreground,
        // which is the only place it belongs.
        if (!enabled) ScreenControlService.instance?.setBubbleVisible(false)
    }

    fun setBackgroundWake(enabled: Boolean) {
        userPrefs.backgroundWake = enabled
        DebugLog.log(DebugLog.Stage.SESSION, "background wake word ${if (enabled) "on" else "off"}")
        // Apply immediately: if we're in the foreground the engine owns the mic and
        // the service stays off until we background; if turned off, stop it now.
        if (!enabled) HotwordService.stop(appContext)
    }

    fun themeId(): String = userPrefs.themeId

    fun saveThemeId(id: String) {
        userPrefs.themeId = id
        // The floating orb draws itself from a plain-int copy of the palette and
        // has no idea a preference changed. Without this it keeps the old theme's
        // colours until the accessibility service happens to restart — which, on
        // a phone, can be days.
        ScreenControlService.instance?.bubble?.refreshTheme()
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

    /**
     * Wake JARVIS by hand — tapping the orb — instead of saying the wake word.
     * A deliberate tap is as clear a summons as "Hey JARVIS", and it works when
     * saying it out loud would be awkward. No-op if already engaged or mid-turn.
     */
    fun wake() {
        if (turn.micGated || isEngaged() || session.owner == MicOwner.NONE) return
        awake = true
        DebugLog.log(DebugLog.Stage.THINK, "woken by tap")
        applyMicOwner() // re-enters listen(), which now shows Listening and arms the idle timer
    }

    private fun listen() {
        if (!voice.isAvailable()) {
            set { it.copy(orb = OrbState.Error, status = "Speech recognition unavailable") }
            return
        }
        // No need to clear the turn here: the only caller is [applyMicOwner],
        // which has already returned early if one is in flight.
        set { it.copy(orb = idleOrb(), status = idleStatus(), amplitude = 0f) }
        // Only count down to sleep while awake and outside a session; a session is
        // its own "keep listening" contract, and asleep there is nothing to time.
        main.removeCallbacks(sleepTimer)
        if (awake && !session.isActive) main.postDelayed(sleepTimer, SLEEP_AFTER_MS)
        voice.startListening()
    }

    /**
     * The owners for which the in-app recogniser is the right listener.
     *
     * Stated as a whitelist rather than `!= NONE` on purpose: this is the one
     * path that reaches `voice.startListening` without going through
     * [applyMicOwner], so a future owner added to [MicOwner] must be granted
     * listening here deliberately rather than inheriting it.
     */
    private fun engineMayListen(): Boolean =
        session.owner == MicOwner.ENGINE || session.owner == MicOwner.SESSION

    private fun restartSoon() {
        if (turn.micGated || !engineMayListen()) return
        main.postDelayed({ if (!turn.micGated && engineMayListen()) voice.startListening() }, RESTART_MS)
    }

    /** Engaged = mid-conversation: already awake, or in a work session. */
    private fun isEngaged(): Boolean = awake || session.isActive

    private fun idleOrb(): OrbState = if (isEngaged()) OrbState.Listening else OrbState.Idle

    private fun idleStatus(): String = if (isEngaged()) "Listening…" else WAKE_HINT

    /**
     * A final transcript arrived. While asleep, everything except the wake word is
     * ignored — not thought about, not spoken to, not acted on. This is the whole
     * point of the wake word, and why the earlier always-on build reacted to
     * speech the user never aimed at it.
     */
    private fun onHeard(text: String, heard: List<String>) {
        if (isEngaged()) {
            stayAwake()
            ask(text, heard = heard)
            return
        }
        val wake = WakeWord.detect(text)
        if (!wake.matched) {
            DebugLog.log(DebugLog.Stage.HEARD, "(asleep) ignored: $text")
            restartSoon()
            return
        }
        DebugLog.log(DebugLog.Stage.THINK, "woke on the wake word")
        awake = true
        if (wake.command.isBlank()) {
            // Just "Hey JARVIS" — acknowledge and listen for the real command.
            speakAck(Acknowledgement.summoned(spokenTurn++))
        } else {
            // "Hey JARVIS, <command>" — handle the command in the same breath.
            ask(wake.command, heard = listOf(wake.command))
        }
    }

    /** Stay awake and (re)start the idle countdown back to sleep. */
    private fun stayAwake() {
        awake = true
        main.removeCallbacks(sleepTimer)
        if (!session.isActive) main.postDelayed(sleepTimer, SLEEP_AFTER_MS)
    }

    private val sleepTimer = object : Runnable {
        override fun run() {
            // Don't fall asleep mid-think/speak; check again once that finishes.
            if (turn.suppressSleep) {
                main.postDelayed(this, SLEEP_AFTER_MS)
                return
            }
            goToSleep()
        }
    }

    private fun goToSleep() {
        if (!awake) return
        awake = false
        main.removeCallbacks(sleepTimer)
        DebugLog.log(DebugLog.Stage.THINK, "back to sleep — waiting for the wake word")
        if (!turn.suppressSleep) set { it.copy(orb = OrbState.Idle, status = WAKE_HINT, transcript = "", reply = "") }
    }

    /**
     * Speak a short acknowledgement with no model round-trip, then listen.
     * Uses the ordinary speak → [onSpokenDone] → listen path so the hand-off is the
     * same one every reply uses — a divergent summons path is exactly what made
     * the wake word unreliable last time.
     */
    private fun speakAck(line: String) {
        main.removeCallbacks(sleepTimer)
        voice.stopListening()
        set { it.copy(orb = OrbState.Speaking, status = "Speaking…", transcript = "", reply = line) }
        speakTurn(line)
    }

    /**
     * Speak [line] and hold the turn until the speaker says it is over.
     *
     * The single way anything in this class speaks. Claiming the turn and saying
     * the words have to happen together — a spoken line whose turn was never
     * claimed leaves the microphone open to hear JARVIS himself, and a claimed
     * turn that never speaks never ends.
     */
    private fun speakTurn(line: String) {
        // The model formats for a screen — `**bold**`, `* bullets`, `# headings`
        // — and text-to-speech reads every one of those characters out loud. A
        // device trace has the user hearing "asterisk asterisk U I testing".
        //
        // Cleaned HERE and nowhere else, for the reason every guard in this class
        // is where it is: this is the single path to the speaker, so no route
        // added later can miss it. The markdown stays in what is DISPLAYED — on
        // the chat screen it is exactly what makes a long answer readable.
        val spoken = SpokenText.plain(line)
        turn.speak(speaker.speak(spoken))
        armWatchdog(turn.currentSeq, spoken)
    }

    /**
     * Releases the turn if no end-of-speech report arrives in the time the line
     * could plausibly take to say.
     *
     * A backstop, not a mechanism — every path through [Speaker] promises a
     * report. It exists because the failure it catches is the worst one this app
     * has: a turn that never ends never reopens the microphone, so JARVIS stops
     * responding altogether and the only fix is force-quitting him.
     */
    private fun armWatchdog(seq: Long, line: String) {
        disarmWatchdog()
        val budget = maxOf(WATCHDOG_MIN_MS, line.length * WATCHDOG_PER_CHAR_MS + WATCHDOG_SLACK_MS)
        guard.postDelayed({
            if (turn.watchdogExpired(seq)) {
                DebugLog.log(
                    DebugLog.Stage.ERROR,
                    "no end-of-speech after ${budget}ms — releasing the turn so JARVIS can hear again",
                )
                onSpokenDone()
            }
        }, budget)
    }

    private fun disarmWatchdog() {
        guard.removeCallbacksAndMessages(null)
    }

    /**
     * Cut JARVIS off mid-sentence and start listening straight away.
     *
     * Ends the turn synchronously rather than waiting for the speaker to confirm
     * it stopped, because [Speaker.stop] only produces a callback when something
     * was actually being spoken.
     *
     * Clearing [pendingScreen] is the part that is easy to miss: an app switch
     * queued behind the reply must die with the reply. Otherwise interrupting
     * looks like it worked — JARVIS goes quiet — and then the phone jumps to
     * another app a moment later anyway.
     */
    fun interrupt() {
        val stoppingTask = taskRunning
        // Speaking, or mid-task. The second half is the fix for a reported
        // failure: an errand is mostly silence — a step running, a model call in
        // flight — and refusing to interrupt outside SPEAKING meant the moment
        // the user most wanted him to stop was the moment he would not.
        //
        // The old comment said cutting off a THINKING turn would be overridden a
        // second later when the request landed. That is no longer true for a
        // task: [errandToken] is bumped below, and every continuation re-checks
        // it, so the in-flight answer is discarded instead of acted on.
        if (turn.phase != TurnPhase.SPEAKING && !stoppingTask) return
        if (stoppingTask) {
            // Bumping the token is what actually stops the loop — the reply
            // already on its way back from the model returns to a post that
            // quietly drops it.
            errandToken++
            agentSteps = 0
            errandSteps = emptyList()
            pendingGoal = ""
            ScreenControlService.instance?.cancelSequence()
            setTaskRunning(false)
            DebugLog.log(DebugLog.Stage.SCREEN, "task stopped — interrupted")
        }
        // Unconditional: after stopping a task the turn can be THINKING, and a
        // turn left anywhere but IDLE keeps the microphone gated — which would
        // leave the user having successfully interrupted into silence.
        turn.interrupt()
        speaker.stop()
        disarmWatchdog()
        pendingScreen = null
        replayingRoute = false
        DebugLog.log(DebugLog.Stage.HEARD, "interrupted — listening")
        // Being cut off is the user engaging, not going quiet: stay awake so the
        // thing they interrupted to say needs no wake word. [applyMicOwner] puts
        // the orb and status right on the way into listening.
        stayAwake()
        applyMicOwner()
    }

    private fun ask(userText: String, source: String = "voice", heard: List<String> = emptyList()) {
        turn.think()
        disarmWatchdog()
        // Handling a command means JARVIS is engaged; the sleep timer is cleared by
        // the removeCallbacks below and re-armed when it next returns to listening.
        awake = true
        pendingScreen = null
        replayingRoute = false
        voice.stopListening()
        main.removeCallbacksAndMessages(null)

        DebugLog.log(DebugLog.Stage.HEARD, "($source) $userText")
        // The recogniser's near misses, if any — handed to the model as context so
        // it can recover a mis-heard word ("pic" for "peak") from the conversation
        // and the user's known names. Kept out of the stored turn and the guards,
        // which must match on what the user actually meant, not on a maybe-word.
        val heardHint = Transcript.disambiguationHint(heard)
        if (heardHint != null) {
            DebugLog.log(DebugLog.Stage.HEARD, "unsure of a word — also heard: $heardHint")
        }
        currentGoal = userText
        // A new utterance ends any control the previous one had — drop the tint now
        // so it never lingers; the new command re-shows it if it drives the screen.
        // Any new utterance abandons the errand in flight. Without this, an old
        // loop kept choosing steps while a new command ran — a trace shows the
        // user asking a question at 15:48:32 and the previous errand still
        // tapping at 15:48:37, two loops fighting over one screen.
        errandToken++
        setTaskRunning(false)

        // A question is outstanding and this is the answer to it. Handled here,
        // before the model is involved at all, for the reason the trace showed:
        // asked "I'm about to tap Send, which I can't undo. Shall I?" the user
        // said "do it", the reply went back to the model, and the model wrote a
        // DIFFERENT message, typed it and sent it. Permission was given for one
        // specific act, so that act is what runs.
        val awaiting = pendingConfirm
        if (awaiting != null) {
            when (Confirmation.answerFor(userText)) {
                Confirmation.Answer.YES -> {
                    pendingConfirm = null
                    DebugLog.log(DebugLog.Stage.SCREEN, "confirmed — running $awaiting exactly")
                    addTurn(ChatTurn(ChatTurn.USER, userText))
                    ScreenControlService.instance?.runSteps(listOf(awaiting), recover = false)
                    return
                }
                Confirmation.Answer.NO -> {
                    pendingConfirm = null
                    DebugLog.log(DebugLog.Stage.SCREEN, "declined — $awaiting dropped")
                    addTurn(ChatTurn(ChatTurn.USER, userText))
                    val line = "Left it alone."
                    addTurn(ChatTurn(ChatTurn.ASSISTANT, line))
                    set { it.copy(orb = OrbState.Speaking, status = "Speaking…", transcript = userText, reply = line) }
                    speakTurn(line)
                    return
                }
                // Anything that is not plainly yes or no is a new request, and
                // guessing either way is worse than both: yes fires an
                // irreversible action nobody authorised, no silently drops
                // something they asked for.
                Confirmation.Answer.NEITHER -> pendingConfirm = null
            }
        }

        // "Thank you Jarvis" ends the work session. Handled here, before the model
        // is called, so it always works even if the network is down.
        if (session.endIfStopPhrase(userText)) {
            DebugLog.log(DebugLog.Stage.SESSION, "ended by stop phrase")
            // Dismissing JARVIS puts it back to sleep: the next command needs the
            // wake word again, which is what "thank you Jarvis" should mean.
            awake = false
            val farewell = Acknowledgement.farewell(spokenTurn++)
            addTurn(ChatTurn(ChatTurn.USER, userText))
            addTurn(ChatTurn(ChatTurn.ASSISTANT, farewell))
            DebugLog.log(DebugLog.Stage.SPOKE, farewell)
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", transcript = userText, reply = farewell) }
            speakTurn(farewell)
            return
        }

        addTurn(ChatTurn(ChatTurn.USER, userText))
        set {
            it.copy(orb = OrbState.Thinking, status = "Thinking…", transcript = userText, reply = "", amplitude = 0f)
        }

        if (!Brain.hasKey()) {
            val msg = "No AI key yet. Add a GROQ_API_KEY secret in GitHub to switch on my brain."
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = msg) }
            speakTurn(msg)
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
            val plan = ScreenActions.Plan(
                clean = Acknowledgement.forPlan(steps, spokenTurn++),
                steps = steps,
            )
            pendingGoal = userText
            pendingScreen = plan
            replayingRoute = true
            // Reset the same counters the model path resets. A replay still runs
            // through the service with recovery enabled, and recovery consults the
            // agent budget — left over from a previous errand, it could refuse to
            // recover a replayed route before it had taken a single step.
            stepsTaken = steps
            agentSteps = 0
            addTurn(ChatTurn(ChatTurn.ASSISTANT, plan.clean))
            DebugLog.log(DebugLog.Stage.SPOKE, plan.clean)
            set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = plan.clean) }
            speakTurn(plan.clean)
            return
        }

        val history = conversation.takeLast(MAX_CONTEXT_TURNS)
        scope.launch {
            try {
                // Commands go to the small model; anything that needs thinking
                // goes to the big one. Groq's quotas are per model, so this keeps
                // the 70b allowance for the turns that actually need it.
                val tier = ModelRouter.tierFor(userText)
                val prompt = buildContext(heardHint)
                val raw = Brain.generate(history, prompt, tier)
                DebugLog.log(DebugLog.Stage.REPLY, "${Brain.providerName()}/${tier.name}: $raw")

                // Strip any leftover end-marker the model may add; we simply keep listening.
                val reply = raw.replace("<<END>>", "", ignoreCase = true)
                // Pull out and run the two command families (calendar + screen).
                val (afterCal, actions) = CalendarActions.parse(reply)
                val (afterAlarm, rawAlarms) = AlarmActions.parse(afterCal)
                // An alarm nobody asked for is a real noise at a real time, and
                // the user finds out when it goes off. A trace had "play Beat It"
                // set a ten-minute timer called "nap". The previous assistant turn is
                // passed so a bare "6 o'clock" confirms an alarm JARVIS just asked the
                // time for — another trace dropped exactly that and never set it.
                val priorPrompt = history.lastOrNull { it.role == ChatTurn.ASSISTANT }?.content.orEmpty()
                val alarms = AlarmGuard.apply(userText, rawAlarms, priorPrompt)
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
                // What he SAYS. The model's own sentence wins whenever there is
                // one — [Acknowledgement] is only the floor for a reply that was
                // nothing but markers, and it says what is actually happening
                // ("Opening Spotify") instead of the same two syllables every
                // time.
                spokenTurn++
                val spoken = when {
                    needsPerm ->
                        "To control the screen, open Accessibility settings, go to Downloaded apps, and switch on JARVIS Screen Control, then ask me again."
                    clean.isNotBlank() -> listOfNotNull(clean, spendNote, volumeNote).joinToString(" ")
                    filesMade > 0 -> Acknowledgement.forFile(spokenTurn)
                    alarmsSet > 0 ->
                        listOfNotNull(Acknowledgement.forAlarm(spokenTurn), volumeNote).joinToString(" ")
                    added > 0 || deleted > 0 -> Acknowledgement.forCalendar(added, deleted, spokenTurn)
                    plan.hasAction ->
                        listOfNotNull(Acknowledgement.forPlan(plan.steps, spokenTurn), spendNote)
                            .joinToString(" ")
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
                speakTurn(spoken)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                DebugLog.log(DebugLog.Stage.ERROR, "Brain error: $detail")
                // The provider's full paragraph belongs in the trace, not on the
                // orb — on screen it swamps everything and reads as noise.
                val shown = if (detail.length > 120) detail.take(117) + "…" else detail
                set { it.copy(orb = OrbState.Error, status = "Brain error", reply = shown) }
                main.postDelayed({
                    // Nothing was ever spoken, so no end-of-speech report is
                    // coming — release the turn here or the microphone stays shut.
                    turn.reconcile(engineIsSpeaking = false)
                    onSpokenDone()
                }, 3000L)
            }
        }
    }

    /**
     * The turn is over. Every caller has already moved [turn] to
     * [TurnPhase.IDLE] — this only acts on what was waiting for it to end.
     */
    private fun onSpokenDone() {
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

    // Named `entry` rather than `turn` so it cannot shadow the turn-state field.
    private fun addTurn(entry: ChatTurn) {
        conversation.add(entry)
        while (conversation.size > MAX_STORED_TURNS) conversation.removeAt(0)
        store.save(conversation)
        set { it.copy(messages = conversation.toList()) }
    }

    private suspend fun buildContext(heardHint: String? = null): String = withContext(Dispatchers.IO) {
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
        // A device trace (2026-08-18) has JARVIS insisting "I don't have visual
        // access to your screen" five times in a row while the accessibility
        // service was connected and this very line was running. Nothing in the
        // trace could settle whether the listing had reached the model or not,
        // because the context is assembled silently. Log what was read — the app
        // line only, never the contents, which are the user's private screen.
        DebugLog.log(
            DebugLog.Stage.THINK,
            if (screen.isBlank()) {
                "screen: nothing readable from here"
            } else {
                "screen: ${screen.length} chars — ${screen.lineSequence().firstOrNull().orEmpty().take(60)}"
            },
        )
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

        // Speech-to-text is imperfect. When the recogniser was unsure of a word,
        // its near misses are offered here so the model can prefer the reading
        // that fits — rather than acting on a word that was plainly mis-heard.
        val misheard = heardHint?.let {
            "Speech-to-text is imperfect: for the user's latest message the recogniser also " +
                "heard $it. If a word seems out of place, prefer the alternative that fits the " +
                "conversation and the user's known names. Never read these alternatives aloud."
        }

        listOfNotNull(
            "Current date/time: $now. $schedule When asked about the schedule, use ONLY this " +
                "real calendar data and do not invent events. When rescheduling or deleting, " +
                "identify the exact event from this list.",
            screenContext,
            misheard,
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
        // Dim the screen for the whole errand/sequence: the user should be able to
        // see that JARVIS is the one acting. Only for steps that actually drive the
        // screen — merely launching an app is not "taking control". Cleared when
        // the work finishes (say), is superseded (ask), or the turn ends.
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
        // A replayed route is already known to work — run it as the sequence it was
        // stored as. Driving it would discard exactly the steps it was kept for.
        if (plan.needsAccessibility && !replayingRoute && AgentLoop.isErrand(plan.steps)) {
            // An errand inside an app is not planned, it is driven. Everything
            // after the first step was guessed against a screen that did not
            // exist yet — which is why a Blinkit trace kept tapping a control
            // called "Search" in an app whose search box is labelled "Search for
            // atta, dal, coke and more". Open the app, then look before each move.
            val goal = pendingGoal
            val opens = AgentLoop.opensIn(plan.steps)
            // The app this errand belongs to — the loop must not wander out of it.
            val errandApp = AgentLoop.appOf(plan.steps)
            DebugLog.log(
                DebugLog.Stage.SCREEN,
                "errand: opening, then deciding each step from the screen " +
                    "(the planned ${plan.steps.size} steps are only a suggestion)",
            )
            // Seeded with the launches already performed, so the loop cannot
            // decide to open an app that is already in front — which it did
            // three times running before stalling out.
            errandSteps = opens
            val token = errandToken
            ScreenControlService.instance?.runSteps(opens, recover = false) { ok, _ ->
                main.post {
                    // The launch itself failed — there is no app to drive. A trace
                    // shows <<OPEN|Search>> (a control mistaken for an app) failing
                    // honestly and the loop carrying on regardless, into a screen
                    // JARVIS had never left, where it promptly got stuck. Report
                    // the real reason instead of the loop's generic dead end.
                    if (!ok) {
                        DebugLog.log(DebugLog.Stage.SCREEN, "errand abandoned — the app never opened")
                        say(AgentLoop.couldNotOpenMessage(errandApp))
                        return@post
                    }
                    driveErrand(goal, token, lastFailed = null, app = errandApp)
                }
            }
            return ScreenOutcome.DISPATCHED
        }
        if (plan.needsAccessibility) {
            // Run the whole ordered sequence (open -> tap -> type -> enter) via the service.
            val goal = pendingGoal
            // A route that came FROM the playbook must not be written back into it.
            // Re-learning a replay makes a template reinforce itself on every use,
            // which is how "you didnt do anything" reached two uses in one session.
            val learnable = !replayingRoute
            ScreenControlService.instance?.runSteps(plan.steps) { ok, ranClean ->
                // The sequence is over — stop showing the control tint.
                // Only a sequence that ran clean is worth keeping. Routes that
                // needed recovery are exactly the ones whose steps were wrong.
                //
                // `ok` alone was not that test: recovery rescues a run and then
                // reports success, so a trace shows three routes learned in one
                // session — "did you are", "search box" — each stored from a
                // sequence whose typing step had just failed.
                if (ok && ranClean && learnable && goal.isNotBlank()) {
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
            // Opens only. Launching an app needs no accessibility permission, and
            // this branch used to say so by calling [AppLauncher] straight — which
            // quietly skipped the guard that lives in the service.
            //
            // From a device trace (2026-08-18): "open YouTube" opened YouTube, a
            // video started, and thirty seconds later "can you open YouTube"
            // RELAUNCHED it, throwing the user back to the home feed. The
            // "already in X — not relaunching" check that exists to prevent
            // exactly that sits in [ScreenControlService.runSteps], and a bare
            // Open never went through it — so the protection was real, tested,
            // and on a road this plan does not travel.
            //
            // Recovery stays off: there is nothing to recover from a launch, and
            // re-planning around one would put a second loop on the screen.
            val service = ScreenControlService.instance
            if (service != null) {
                service.runSteps(plan.steps, recover = false)
            } else {
                // No accessibility service bound, so nothing can read what is in
                // front. Launching blind is still better than refusing — this is
                // the one path where a relaunch cannot be ruled out.
                val aliases = com.jarvis.os.control.AppAliases.parse(
                    userPrefs.customInstructions.lines() + userPrefs.learnedFacts(),
                )
                for (step in plan.steps) {
                    if (step is ScreenStep.Open) AppLauncher.launch(appContext, step.app, aliases)
                }
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
    private fun driveErrand(
        goal: String,
        token: Int,
        lastFailed: ScreenStep?,
        app: String = "",
        stalls: Int = 0,
        lastScreen: String = "",
        nudges: Int = 0,
        renderWaits: Int = 0,
    ) {
        // A newer command has taken over. Without this the old loop kept picking
        // steps while the new one ran, and two loops fought over one screen.
        if (token != errandToken) {
            DebugLog.log(DebugLog.Stage.SCREEN, "errand abandoned — a newer command took over")
            return
        }
        val service = ScreenControlService.instance
        if (service == null || goal.isBlank()) {
            setTaskRunning(false)
            return
        }
        // From here the loop owns the screen until it says otherwise, and the
        // user must be able to stop it at any point in between.
        setTaskRunning(true)
        if (AgentLoop.exhausted(agentSteps)) {
            DebugLog.log(DebugLog.Stage.SCREEN, "agent budget spent")
            setTaskRunning(false)
            say(AgentLoop.exhaustedMessage(goal))
            return
        }
        val screen = service.describeScreen()
        // The app was launched a moment ago and is very likely still drawing.
        // Deciding now hands the model a splash screen and asks for its next move,
        // which a trace shows it answering with a reflexive <<BACK>> or a <<PICK>>
        // against a list of one. Only before the first action: after that, a sparse
        // screen is real information rather than a rendering delay.
        if (agentSteps == 0 && renderWaits < AgentLoop.MAX_RENDER_WAITS &&
            AgentLoop.looksUnrendered(screen)
        ) {
            DebugLog.log(
                DebugLog.Stage.SCREEN,
                "errand: waiting for the app to finish opening " +
                    "(${renderWaits + 1}/${AgentLoop.MAX_RENDER_WAITS})",
            )
            main.postDelayed(
                { driveErrand(goal, token, lastFailed, app, stalls, lastScreen, nudges, renderWaits + 1) },
                RENDER_WAIT_MS,
            )
            return
        }
        // Nothing moved. Acting again from an identical screen is how the loop
        // produced eighteen taps that changed nothing.
        val stalled = if (screen == lastScreen) stalls + 1 else 0
        if (stalled >= AgentLoop.STALL_LIMIT) {
            DebugLog.log(DebugLog.Stage.SCREEN, "errand stopped — the screen stopped changing")
            setTaskRunning(false)
            say(AgentLoop.blockedMessage(goal, AgentLoop.NO_STEP))
            return
        }
        // Logged BEFORE the call, not after. A device trace on 2026-08-18 has a
        // sixteen-second hole here — the app opened Amazon Music, said nothing,
        // and the user gave up — and nothing in the log said whether the request
        // had been sent, was still in flight, or had died. Every other line in
        // that trace is an event that already happened, which is exactly why a
        // hang leaves no trace at all. This line, and the elapsed time on the
        // matching line below, are what will name the cause next time.
        DebugLog.log(DebugLog.Stage.SCREEN, "errand: asking for the next move")
        val askedAt = System.currentTimeMillis()
        scope.launch {
            val move = try {
                val answer = withTimeout(AGENT_STEP_TIMEOUT_MS) {
                    Brain.generate(
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
                }
                DebugLog.log(
                    DebugLog.Stage.SCREEN,
                    "errand: next move answered in ${System.currentTimeMillis() - askedAt}ms",
                )
                AgentLoop.parseMove(answer, avoid = lastFailed, taken = errandSteps, stayInApp = app, goal = goal)
            } catch (e: TimeoutCancellationException) {
                // The HTTP layer already has a 30s read timeout PER MODEL, and the
                // chain tries more than one — so without a budget across the whole
                // step the user can sit through a minute and a half of silence
                // with the screen tinted. Ending it here means they always get a
                // sentence instead.
                DebugLog.log(
                    DebugLog.Stage.ERROR,
                    "agent step timed out after ${System.currentTimeMillis() - askedAt}ms",
                )
                AgentMove.Blocked("no answer in time")
            } catch (e: Exception) {
                DebugLog.log(
                    DebugLog.Stage.ERROR,
                    "agent step failed after ${System.currentTimeMillis() - askedAt}ms: " +
                        (e.message ?: e.javaClass.simpleName),
                )
                AgentMove.Blocked(e.message ?: "no answer")
            }

            main.post {
                if (token != errandToken) return@post
                when (move) {
                    is AgentMove.Act -> {
                        agentSteps += 1
                        errandSteps = errandSteps + move.step
                        stepsTaken = stepsTaken + move.step
                        DebugLog.log(
                            DebugLog.Stage.SCREEN,
                            "errand step $agentSteps/${AgentLoop.MAX_STEPS}: ${move.step}",
                        )
                        // recover = false: this loop IS the recovery, and two of
                        // them on one screen would fight.
                        service.runSteps(listOf(move.step), recover = false) { ok, _ ->
                            main.post {
                                driveErrand(
                                    goal = goal,
                                    token = token,
                                    lastFailed = if (ok) null else move.step,
                                    app = app,
                                    stalls = stalled,
                                    lastScreen = screen,
                                )
                            }
                        }
                    }
                    AgentMove.Done -> {
                        DebugLog.log(DebugLog.Stage.SCREEN, "errand done after $agentSteps steps")
                        setTaskRunning(false)
                        say("That's done.")
                    }
                    is AgentMove.Ask -> {
                        DebugLog.log(DebugLog.Stage.SCREEN, "errand stopped to ask: ${move.question}")
                        setTaskRunning(false)
                        // Held so that "do it" runs THIS, and not whatever the
                        // model would come up with a second time.
                        pendingConfirm = move.pending
                        say(move.question)
                    }
                    is AgentMove.Blocked -> {
                        // A refused move is usually a habit rather than a dead end
                        // — re-opening an app already in front, or going Back the
                        // instant it arrives — so it gets ONE more chance to pick
                        // something else before the errand ends. It costs a round
                        // trip and no taps. A trace shows three errands in a row
                        // dying on their FIRST move to JUST_ARRIVED, each telling
                        // the user JARVIS could not see the screen before it had
                        // tried anything at all.
                        if (move.reason in AgentLoop.NUDGEABLE && nudges == 0) {
                            DebugLog.log(
                                DebugLog.Stage.SCREEN,
                                "errand: ${move.reason} — asking once more",
                            )
                            driveErrand(goal, token, lastFailed, app, stalled, lastScreen, nudges = 1)
                        } else {
                            DebugLog.log(DebugLog.Stage.SCREEN, "errand stuck: ${move.reason}")
                            setTaskRunning(false)
                            say(AgentLoop.blockedMessage(goal, move.reason))
                        }
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
        // Reaching here ends an errand (done, asked, blocked, or out of steps), so
        // JARVIS is no longer driving the screen — drop the control tint.
        // Claims the turn (below, via [speakTurn]) for exactly the same reason
        // [ask] and [speakAck] do. TTS comes out of the MUSIC stream, so while
        // this line is being spoken `AudioManager.isMusicActive` is true;
        // [mediaCheck] skips its own speech via [TurnState.ownVoiceOnStream].
        // Without it the check heard JARVIS talking, concluded the user had
        // started media, and yielded the microphone — a trace shows "audio
        // started — pausing listening" two seconds after every single agent-loop
        // message, so the user could not answer the question JARVIS had just
        // asked them. It also stops the sleep timer firing mid-sentence.
        voice.stopListening()
        addTurn(ChatTurn(ChatTurn.ASSISTANT, line))
        DebugLog.log(DebugLog.Stage.SPOKE, line)
        set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = line) }
        speakTurn(line)
    }

    private fun set(block: (VoiceUiState) -> VoiceUiState) {
        val next = block(_state.value)
        _state.value = next
        // Every state change in the app funnels through here, which is the only
        // reason one line is enough to keep a window in another process's
        // foreground truthful. The bubble ignores repaints it does not need.
        ScreenControlService.instance?.bubble?.setState(next.orb, next.amplitude)
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

        // How long to wait for an end-of-speech report before assuming none is
        // coming. Scaled to the line, because the cost of guessing short is a
        // turn declared over while JARVIS is still talking — which opens the
        // microphone onto his own voice. Generous on purpose: this is a backstop
        // against a stuck assistant, not a timing mechanism.
        const val WATCHDOG_PER_CHAR_MS = 90L
        const val WATCHDOG_SLACK_MS = 3_000L
        const val WATCHDOG_MIN_MS = 4_000L

        // Gap between releasing the barge-in recorder and opening the
        // recogniser. See the hand-off note in applyMicOwner.
        const val MIC_HANDOFF_MS = 200L

        // How long to wait before looking again at an app that has been launched
        // but has not drawn yet. Five of these is ~3s, which covers a cold start
        // without leaving the user waiting on a silent phone.
        const val RENDER_WAIT_MS = 600L

        // The whole budget for ONE agent-loop question, across every model the
        // chain tries. Deliberately shorter than the transport's own ceiling
        // (15s connect + 30s read, PER MODEL): the point is not to be generous,
        // it is that a next-move question which cannot be answered in this long
        // is not going to be answered usefully at all, and every second past it
        // is the user staring at a tinted screen hearing nothing.
        const val AGENT_STEP_TIMEOUT_MS = 25_000L

        // Wake-word idle timeout: after this long awake with nothing said, JARVIS
        // returns to sleep and needs "Hey JARVIS" again. Long enough to think
        // between follow-ups, short enough that it isn't left wide open.
        const val SLEEP_AFTER_MS = 18_000L

        // Shown while asleep, so the screen says how to get JARVIS's attention.
        const val WAKE_HINT = "Say “Hey JARVIS”"
    }
}
