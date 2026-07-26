package com.jarvis.os.assistant

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.jarvis.os.ai.Brain
import com.jarvis.os.calendar.CalAction
import com.jarvis.os.calendar.CalendarActions
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.calendar.CalendarWriter
import com.jarvis.os.control.AppLauncher
import com.jarvis.os.control.ScreenActions
import com.jarvis.os.control.ScreenControlService
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.data.ConversationStore
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
 */
class AssistantEngine(context: Context) {

    private val appContext = context.applicationContext
    private val store = ConversationStore(appContext)
    private val conversation: MutableList<ChatTurn> = store.load()

    private val _state = mutableStateOf(VoiceUiState(status = "Starting…", messages = conversation.toList()))
    val state: State<VoiceUiState> get() = _state

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val voice = VoiceController(appContext)
    private val speaker = Speaker(appContext)

    private var micGranted = false
    private var visible = false
    private var busy = false // thinking or speaking — do not listen

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
    }

    fun onMicPermission(granted: Boolean) {
        micGranted = granted
        if (granted) {
            if (visible && !busy) listen()
        } else {
            set { it.copy(orb = OrbState.Error, status = "Microphone permission needed", amplitude = 0f) }
        }
    }

    fun resume() {
        visible = true
        if (micGranted && !busy) listen()
    }

    fun pause() {
        visible = false
        main.removeCallbacksAndMessages(null)
        voice.stopListening()
        speaker.stop()
        set { it.copy(amplitude = 0f) }
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        voice.destroy()
        speaker.shutdown()
        scope.cancel()
    }

    fun clearConversation() {
        conversation.clear()
        store.clear()
        set { it.copy(messages = emptyList(), transcript = "", reply = "") }
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
        if (!visible || busy || !micGranted) return
        main.postDelayed({ if (visible && !busy && micGranted) voice.startListening() }, RESTART_MS)
    }

    private fun ask(userText: String) {
        busy = true
        voice.stopListening()
        main.removeCallbacksAndMessages(null)

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

        val history = conversation.takeLast(MAX_CONTEXT_TURNS)
        scope.launch {
            try {
                val raw = Brain.generate(history, buildContext())
                // Strip any leftover end-marker the model may add; we simply keep listening.
                val reply = raw.replace("<<END>>", "", ignoreCase = true)
                // Pull out and run the two command families (calendar + screen).
                val (afterCal, actions) = CalendarActions.parse(reply)
                val plan = ScreenActions.parse(afterCal)
                val clean = plan.clean
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
                val screen = withContext(Dispatchers.IO) { executeScreen(plan) }
                val spoken = when {
                    screen == ScreenOutcome.NEEDS_PERMISSION ->
                        "To control the screen, switch JARVIS on under Accessibility in Settings, then ask me again."
                    clean.isNotBlank() -> clean
                    added > 0 && deleted > 0 -> "Done, I've rescheduled it."
                    added > 0 -> "Okay, I've added it to your calendar."
                    deleted > 0 -> "Done, I've removed it from your calendar."
                    screen == ScreenOutcome.DISPATCHED -> "On it."
                    else -> reply
                }
                addTurn(ChatTurn(ChatTurn.ASSISTANT, spoken))
                set { it.copy(orb = OrbState.Speaking, status = "Speaking…", reply = spoken) }
                speaker.speak(spoken)
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                set { it.copy(orb = OrbState.Error, status = "Brain error", reply = detail) }
                main.postDelayed({ onSpokenDone() }, 3000L)
            }
        }
    }

    private fun onSpokenDone() {
        busy = false
        if (visible && micGranted) {
            listen()
        } else {
            set { it.copy(orb = OrbState.Idle, status = "Paused", amplitude = 0f) }
        }
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
        "Current date/time: $now. $schedule When asked about the schedule, use ONLY this " +
            "real calendar data and do not invent events. When rescheduling or deleting, " +
            "identify the exact event from this list."
    }

    private enum class ScreenOutcome { NONE, DISPATCHED, NEEDS_PERMISSION }

    /**
     * Runs any screen-control actions the model asked for: opens an app and/or
     * taps a visible control. Opening an app needs no special permission; tapping
     * needs the accessibility service — if it's off, send the user to settings.
     */
    private fun executeScreen(plan: ScreenActions.Plan): ScreenOutcome {
        if (!plan.hasAction) return ScreenOutcome.NONE
        if (plan.tapLabel != null && !ScreenControlService.isRunning()) {
            openAccessibilitySettings()
            return ScreenOutcome.NEEDS_PERMISSION
        }
        var launchedPackage: String? = null
        if (plan.openApp != null) {
            launchedPackage = AppLauncher.launch(appContext, plan.openApp)
        }
        if (plan.tapLabel != null) {
            ScreenControlService.instance?.tapWhenReady(launchedPackage, plan.tapLabel)
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

    private fun set(block: (VoiceUiState) -> VoiceUiState) {
        _state.value = block(_state.value)
    }

    private companion object {
        const val RESTART_MS = 400L // gap before restarting the recogniser after no input
        const val MAX_CONTEXT_TURNS = 20 // turns sent to the AI as context
        const val MAX_STORED_TURNS = 200 // turns kept on disk
    }
}
