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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop's assistant: holds the conversation as Compose state, sends a turn
 * through the SAME Worker client the phone uses, and applies the reply via the
 * pure [DesktopTurn]. The Worker is preferred (it holds the model key and meters
 * the account); a direct Groq key is the dev-only fallback, as on the phone.
 */
class DesktopAssistant(
    private val scope: CoroutineScope,
    private val store: ChatStore = ChatStore(),
) {
    private val initial = store.load()

    var turns by mutableStateOf(initial.turns)
        private set
    var facts by mutableStateOf(initial.facts)
        private set
    var thinking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usageLine by mutableStateOf<String?>(null)
        private set

    val configured: Boolean get() = ProxyClient.isConfigured() || GroqClient.hasKey()

    val statusLine: String
        get() = when {
            ProxyClient.isConfigured() -> "Connected · ${Identity.plan()} plan"
            GroqClient.hasKey() -> "Direct Groq (dev key)"
            else -> "Not connected"
        }

    /** Returns true if the message was accepted (so the input can be cleared). */
    fun send(input: String): Boolean {
        val message = input.trim()
        if (message.isEmpty() || thinking) return false
        if (!configured) {
            error = "No server configured — see the banner above."
            return false
        }
        error = null
        turns = turns + ChatTurn(ChatTurn.USER, message)
        persist()
        thinking = true
        scope.launch {
            try {
                val history = turns.takeLast(DesktopTurn.MAX_CONTEXT_TURNS)
                val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
                val context = DesktopTurn.context(now, formatMemory("", facts))
                val raw = withContext(Dispatchers.IO) {
                    if (ProxyClient.isConfigured()) ProxyClient.generate(history, context)
                    else GroqClient.generate(history, context)
                }
                val result = DesktopTurn.process(raw)
                facts = DesktopTurn.applyMemory(facts, result.memory)
                turns = turns + ChatTurn(ChatTurn.ASSISTANT, result.text.ifBlank { "…" })
                persist()
                UsageStats.today()?.let {
                    usageLine = "${UsageStats.format(it.remaining)} tokens left today"
                }
            } catch (e: Exception) {
                DebugLog.log(DebugLog.Stage.ERROR, "desktop turn failed: ${e.javaClass.simpleName}")
                // ProxyException already carries a human, speakable sentence.
                error = e.message ?: "Something went wrong — try again."
            } finally {
                thinking = false
            }
        }
        return true
    }

    fun clearConversation() {
        if (thinking) return
        turns = emptyList()
        error = null
        persist()
    }

    fun forgetEverything() {
        facts = emptyList()
        persist()
    }

    private fun persist() {
        runCatching { store.save(ChatStore.State(turns, facts)) }
            .onFailure { DebugLog.log(DebugLog.Stage.ERROR, "could not save chat: ${it.javaClass.simpleName}") }
    }
}
