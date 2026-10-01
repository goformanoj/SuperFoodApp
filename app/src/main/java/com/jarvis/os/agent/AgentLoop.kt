package com.jarvis.os.agent

import com.jarvis.os.data.ChatTurn
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone agent's turn (AGENT_PLAN §7, phone side): ask the model; if it wants tools, run
 * them locally — gated by risk IN CODE — hand the results back, and repeat until it
 * answers in words or the step budget runs out. Ported from the laptop's own
 * `desktop/.../agent/AgentLoop.kt`.
 *
 * Kept free of UI and network so the whole flow is tested (AgentLoopTest) with a scripted
 * model: which tools ran, what was refused, what was asked for approval.
 *
 * Not to be confused with `com.jarvis.os.assistant.AgentLoop`: that one is a narrow,
 * existing state machine purely for recovering from a failed screen-tap step (it parses
 * one `AgentMove` — Act/Done/Ask/Blocked — from free text). This class is the
 * general tool-calling loop; the two solve unrelated problems and happen to share a name,
 * the same way the laptop's own `AgentLoop` does with this one.
 */
class AgentLoop(
    private val tools: ToolBox,
    /** One model step (production: [AgentClient.step]). */
    private val step: suspend (messages: JSONArray, tools: JSONArray) -> AgentClient.Reply,
    /** Asks the user about a step that needs their OK (irreversible); true = approved. */
    private val approve: suspend (Ask) -> Boolean,
    /** Reports each executed (or refused) step, for the chat's step cards. */
    private val onStep: (AgentClient.ToolCall, ToolBox.Result) -> Unit,
    private val maxSteps: Int = MAX_STEPS,
) {
    suspend fun run(history: List<ChatTurn>): String {
        val messages = JSONArray()
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val schemas = tools.schemas()
        val noTools = JSONArray()
        // Ported from the laptop's own AgentLoop.kt (2026-10-01, found live there via its
        // eval harness — see desktop's JARVIS_MEMORY.md for the full story) ahead of this
        // loop ever going live on the phone, so it doesn't ship with a known bug already
        // fixed once. A call that already failed this turn, repeated with the identical
        // arguments, is short-circuited rather than actually re-run.
        val failedBefore = mutableSetOf<Pair<String, String>>()
        var failureCount = 0
        val callCounts = mutableMapOf<String, Int>()
        val doneSoFar = mutableListOf<String>()
        repeat(maxSteps) { i ->
            val lastStep = i == maxSteps - 1
            // The nudges below are just a prompt asking nicely and a persistent model can
            // ignore them; the LAST step is the real, code-level guarantee (Rule 6): no
            // tools are offered, so there's nothing left to call and the model MUST answer
            // in words using whatever the turn already learned.
            val reply = step(messages, if (lastStep) noTools else schemas)
            if (reply.toolCalls.isEmpty()) return reply.text.ifBlank { fallback(doneSoFar) }
            if (lastStep) return fallback(doneSoFar)   // nothing was offered — never trust a stray tool call blindly
            messages.put(AgentClient.assistantToolMessage(reply.text, reply.toolCalls))
            for (call in reply.toolCalls) {
                val key = call.name to call.arguments
                val result = if (key in failedBefore) {
                    ToolBox.Result(
                        false,
                        JSONObject().put("ok", false).put(
                            "error",
                            "This exact action already failed earlier in this turn with the same arguments — repeating it will fail the same way again. Try something different, or tell the user what happened instead of retrying.",
                        ).toString(),
                        "Skipped repeating a failing step: ${call.name}",
                    )
                } else {
                    runOne(call)
                }
                if (result.ok) doneSoFar += result.summary else { failedBefore += key; failureCount++ }
                val timesThisTool = callCounts.merge(call.name, 1, Int::plus)!!
                onStep(call, result)
                val nudge = when {
                    failureCount >= WRAP_UP_AFTER_FAILURES ->
                        "Several actions have failed this turn. If a genuinely different approach won't help, stop here and tell the user what you found and what went wrong, rather than trying more variations."
                    timesThisTool >= WRAP_UP_AFTER_REPEATS ->
                        "“${call.name}” has now been called $timesThisTool times this turn. If you already have enough to answer, do that now instead of calling it again."
                    else -> null
                }
                val forModel = if (nudge == null) result.forModel else
                    runCatching { JSONObject(result.forModel) }.getOrNull()?.put("note", nudge)?.toString() ?: result.forModel
                messages.put(AgentClient.toolResultMessage(call.id, forModel))
            }
        }
        return fallback(doneSoFar)
    }

    /** A real answer when the budget runs out, not just an apology — says what actually happened, if anything did. */
    private fun fallback(doneSoFar: List<String>): String = if (doneSoFar.isEmpty()) {
        "I stopped there — that was taking more steps than I allow myself in one go. Tell me if you want me to carry on."
    } else {
        "I ran out of steps before finishing, but here's what I did: " + doneSoFar.joinToString("; ") + ". Let me know if you'd like me to carry on."
    }

    private suspend fun runOne(call: AgentClient.ToolCall): ToolBox.Result {
        val spec = tools.spec(call.name)
            ?: return ToolBox.Result(false, JSONObject().put("ok", false).put("error", "No such tool.").toString(), "Tried an unknown action “${call.name}”")
        if (spec.risk.needsApproval) {
            val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
            val description = tools.describe(call.name, args)
            if (!approve(Ask(description, tools.approvalNote(call.name)))) {
                return ToolBox.Result(
                    false,
                    JSONObject().put("ok", false).put("error", "The user declined this. Do not retry it; acknowledge briefly.").toString(),
                    "Declined: $description",
                )
            }
        }
        return tools.execute(call.name, call.arguments)
    }

    /** What the approval card shows: the step, and what approving it means. */
    data class Ask(val description: String, val note: String)

    companion object {
        const val MAX_STEPS = 6
        /** How many real failures in one turn before every further failure also says "stop probing, report instead". */
        const val WRAP_UP_AFTER_FAILURES = 2
        /** How many times the SAME tool can be called in one turn before it starts saying "you may already have enough". */
        const val WRAP_UP_AFTER_REPEATS = 3
    }
}
