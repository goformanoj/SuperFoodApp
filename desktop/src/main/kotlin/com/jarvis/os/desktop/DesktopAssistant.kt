package com.jarvis.os.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jarvis.os.ai.GroqClient
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.data.formatMemory
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.desktop.ChatStore.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The desktop's assistant: conversations as Compose state, each turn sent through the
 * SAME Worker client the phone uses, the reply applied via the pure [DesktopTurn].
 * The Worker is preferred (it holds the model key and meters the account); a direct
 * Groq key is the dev-only fallback, as on the phone.
 *
 * `activeId == null` is a fresh, not-yet-saved chat: it becomes a conversation on its
 * first message, so empty chats never litter the sidebar.
 */
class DesktopAssistant(
    private val scope: CoroutineScope,
    private val store: ChatStore = ChatStore(),
) {
    private val initial = store.load()

    var conversations by mutableStateOf(initial.conversations)
        private set
    var activeId by mutableStateOf(initial.conversations.firstOrNull()?.id)
        private set
    var facts by mutableStateOf(initial.facts)
        private set
    /** The conversation a reply is being written for, or null when idle. */
    var thinkingIn by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usage by mutableStateOf<UsageStats.Daily?>(null)
        private set
    /** How long the last reply took, end to end (Worker round trip + model). Real, for the HUD. */
    var lastLatencyMs by mutableStateOf<Long?>(null)
        private set

    val messageCount: Int get() = conversations.sumOf { it.turns.size }

    val active: Conversation? get() = conversations.firstOrNull { it.id == activeId }
    val turns: List<ChatTurn> get() = active?.turns.orEmpty()
    val thinking: Boolean get() = thinkingIn != null
    val thinkingHere: Boolean get() = thinkingIn != null && thinkingIn == activeId

    val configured: Boolean get() = ProxyClient.isConfigured() || GroqClient.hasKey()
    val plan: String get() = if (ProxyClient.isConfigured()) Identity.plan() else "dev"

    fun newChat() {
        activeId = null
        error = null
    }

    fun select(id: String) {
        activeId = id
        error = null
    }

    fun delete(id: String) {
        if (thinkingIn == id) return
        conversations = conversations.filterNot { it.id == id }
        if (activeId == id) activeId = null
        persist()
    }

    /** Returns true if the message was accepted (so the input can be cleared). */
    fun send(input: String): Boolean {
        val message = input.trim()
        if (message.isEmpty() || thinking) return false
        if (!configured) {
            error = "Not connected to the JARVIS server — see Settings."
            return false
        }
        error = null
        val id = activeId ?: UUID.randomUUID().toString()
        val userTurn = ChatTurn(ChatTurn.USER, message)
        upsert(id) { c ->
            val turns = c?.turns.orEmpty() + userTurn
            Conversation(id, c?.title ?: DesktopTurn.titleFor(turns), turns, System.currentTimeMillis())
        }
        activeId = id
        persist()
        thinkingIn = id
        scope.launch {
            try {
                val history = conversations.first { it.id == id }.turns.takeLast(DesktopTurn.MAX_CONTEXT_TURNS)
                val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
                val context = DesktopTurn.context(now, formatMemory("", facts))
                val started = System.currentTimeMillis()
                val raw = withContext(Dispatchers.IO) {
                    if (ProxyClient.isConfigured()) ProxyClient.generate(history, context)
                    else GroqClient.generate(history, context)
                }
                lastLatencyMs = System.currentTimeMillis() - started
                val result = DesktopTurn.process(raw)
                facts = DesktopTurn.applyMemory(facts, result.memory)
                upsert(id) { c ->
                    val base = c ?: Conversation(id, "New chat", emptyList(), 0)
                    base.copy(turns = base.turns + ChatTurn(ChatTurn.ASSISTANT, result.text.ifBlank { "…" }), updatedMs = System.currentTimeMillis())
                }
                persist()
                usage = UsageStats.today()
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "desktop turn failed: ${e.javaClass.simpleName}")
                // ProxyException already carries a human, speakable sentence.
                error = e.message ?: "Something went wrong — try again."
            } finally {
                thinkingIn = null
            }
        }
        return true
    }

    fun forget(fact: String) {
        facts = facts.filterNot { it == fact }
        persist()
    }

    fun forgetEverything() {
        facts = emptyList()
        persist()
    }

    private fun upsert(id: String, change: (Conversation?) -> Conversation) {
        val existing = conversations.firstOrNull { it.id == id }
        val updated = change(existing)
        conversations = (listOf(updated) + conversations.filterNot { it.id == id }).sortedByDescending { it.updatedMs }
    }

    private fun persist() {
        runCatching { store.save(ChatStore.State(conversations, facts)) }
            .onFailure { DebugLog.log(DebugLog.Stage.ERROR, "could not save chat: ${it.javaClass.simpleName}") }
    }
}
