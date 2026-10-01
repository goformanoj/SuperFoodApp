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
        repeat(maxSteps) {
            val reply = step(messages, schemas)
            if (reply.toolCalls.isEmpty()) return reply.text
            messages.put(AgentClient.assistantToolMessage(reply.text, reply.toolCalls))
            for (call in reply.toolCalls) {
                val result = runOne(call)
                onStep(call, result)
                messages.put(AgentClient.toolResultMessage(call.id, result.forModel))
            }
        }
        return "I stopped there — that was taking more steps than I allow myself in one go. Tell me if you want me to carry on."
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
    }
}
