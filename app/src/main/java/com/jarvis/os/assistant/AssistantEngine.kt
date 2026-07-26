package com.jarvis.os.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.jarvis.os.ai.Brain
import com.jarvis.os.calendar.CalAction
import com.jarvis.os.calendar.CalendarActions
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.calendar.CalendarWriter
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
 * Orchestrates the assistant loop with a wake word and memory:
 *   asleep (listening only for "Hey JARVIS") -> awake -> think -> speak -> ...
 * Keeps a persisted conversation and sends recent turns + a grounding context
 * (today's date and tasks) to the AI. Owns the single [VoiceUiState] the UI
 * observes; driven from the Activity lifecycle.
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
    private var busy = false  // thinking or speaking — do not listen
    private var awake = false // in an active conversation (wake word heard)

    private val sleepRunnable = Runnable { goToSleep() }

    init {
        voice.onReady = {
            if (!busy) {
                if (awake) set { it.copy(orb = OrbState.Listening, status = "Listening…") }
                else set { it.copy(orb = OrbState.Idle, status = WAKE_HINT) }
            }
        }
        voice.onPartial = { text ->
            if (!busy && awake) {
                set { it.copy(orb = OrbState.Listening, status = "Listening…", transcript = text) }
            }
        }
        voice.onAmplitude = { amp -> if (!busy) set { it.copy(amplitude = amp) } }
        voice.onNoInput = { restartSoon() }
        voice.onFatal = { msg -> set { it.copy(orb = OrbState.Error, status = msg, amplitude = 0f) } }
        voice.onFinal = { text -> onFinalTranscript(text) }
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
        awake = false
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
        if (awake) {
            set { it.copy(orb = OrbState.Listening, status = "Listening…", amplitude = 0f) }
            armSleep()
        } else {
            set { it.copy(orb = OrbState.Idle, status = WAKE_HINT, amplitude = 0f) }
        }
        voice.startListening()
    }

    private fun restartSoon() {
        if (!visible || busy || !micGranted) return
        main.postDelayed({ if (visible && !busy && micGranted) voice.startListening() }, 350L)
    }

    private fun onFinalTranscript(text: String) {
        if (awake) {
            cancelSleep()
            ask(text)
            return
        }
        val command = extractAfterWakeWord(text)
        if (command == null) {
            // No wake word — stay asleep and keep listening for it.
            set { it.copy(orb = OrbState.Idle, status = WAKE_HINT, transcript = "", amplitude = 0f) }
            restartSoon()
        } else {
            awake = true
            if (command.isBlank()) respondAck() else ask(command)
        }
    }

    private fun respondAck() {
        busy = true
        voice.stopListening()
        main.removeCallbacksAndMessages(null)
        val ack = "Yes?"
        set { it.copy(orb = OrbState.Speaking, status = "Speaking…", transcript = "Hey JARVIS", reply = ack, amplitude = 0f) }
        speaker.speak(ack)
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
                val reply = Brain.generate(history, buildContext())
                // Execute any calendar commands the model emitted, then strip them.
                val (clean, actions) = CalendarActions.parse(reply)
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
                val spoken = when {
                    clean.isNotBlank() -> clean
                    added > 0 && deleted > 0 -> "Done, I've rescheduled it."
                    added > 0 -> "Okay, I've added it to your calendar."
                    deleted > 0 -> "Done, I've removed it from your calendar."
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

    private fun armSleep() {
        main.removeCallbacks(sleepRunnable)
        main.postDelayed(sleepRunnable, SLEEP_MS)
    }

    private fun cancelSleep() {
        main.removeCallbacks(sleepRunnable)
    }

    private fun goToSleep() {
        awake = false
        set { it.copy(orb = OrbState.Idle, status = WAKE_HINT, transcript = "", reply = "", amplitude = 0f) }
    }

    private fun set(block: (VoiceUiState) -> VoiceUiState) {
        _state.value = block(_state.value)
    }

    private companion object {
        const val WAKE_HINT = "Say \"Hey JARVIS\""
        const val SLEEP_MS = 18_000L
        const val MAX_CONTEXT_TURNS = 20 // turns sent to the AI as context
        const val MAX_STORED_TURNS = 200 // turns kept on disk

        // Wake phrases, including common speech-to-text mishearings of "JARVIS".
        val WAKE_WORDS = listOf(
            "hey jarvis", "hey, jarvis", "hi jarvis", "hello jarvis",
            "ok jarvis", "okay jarvis", "hey jervis", "hey travis", "hey jarvis,",
        )

        /** Returns the command after the wake word (may be blank), or null if absent. */
        fun extractAfterWakeWord(raw: String): String? {
            val lower = raw.lowercase()
            for (w in WAKE_WORDS) {
                val idx = lower.indexOf(w)
                if (idx >= 0) {
                    return raw.substring(idx + w.length).trimStart(' ', ',', '.', '!', '?')
                }
            }
            return null
        }
    }
}
