package com.jarvis.os.desktop.agent

import com.jarvis.os.data.ChatTurn
import org.json.JSONArray
import org.json.JSONObject

/**
 * The desktop agent's turn (AGENT_PLAN §4): ask the model; if it wants tools, run them
 * locally — gated by risk IN CODE — hand the results back, and repeat until it answers
 * in words or the step budget runs out.
 *
 * Kept free of UI and network so the whole flow is tested (AgentLoopTest) with a
 * scripted model: which tools ran, what was refused, what was asked for approval.
 */
class AgentLoop(
    private val tools: ToolBox,
    /** One model step (production: [AgentClient.step]). */
    private val step: suspend (messages: JSONArray, context: String, tools: JSONArray) -> AgentClient.Reply,
    /** Asks the user about a step that needs their OK (irreversible, or shares something); true = approved. */
    private val approve: suspend (Ask) -> Boolean,
    /** Reports each executed (or refused) step, for the chat's step cards. */
    private val onStep: (AgentClient.ToolCall, ToolBox.Result) -> Unit,
    private val maxSteps: Int = MAX_STEPS,
) {
    suspend fun run(history: List<ChatTurn>, context: String, sourceConversation: String?): String {
        val messages = JSONArray()
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val schemas = tools.schemas()
        repeat(maxSteps) {
            val reply = step(messages, context, schemas)
            if (reply.toolCalls.isEmpty()) return reply.text
            messages.put(AgentClient.assistantToolMessage(reply.text, reply.toolCalls))
            for (call in reply.toolCalls) {
                val result = runOne(call, sourceConversation)
                onStep(call, result)
                messages.put(AgentClient.toolResultMessage(call.id, result.forModel))
            }
        }
        return "I stopped there — that was taking more steps than I allow myself in one go. Tell me if you want me to carry on."
    }

    private suspend fun runOne(call: AgentClient.ToolCall, sourceConversation: String?): ToolBox.Result {
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
        return tools.execute(call.name, call.arguments, sourceConversation)
    }

    /** What the approval card shows: the step, and what approving it means. */
    data class Ask(val description: String, val note: String)

    companion object {
        const val MAX_STEPS = 6
    }
}

/**
 * A step card as stored in the conversation (role "action"): what happened, whether it
 * worked, and how to undo it. JSON in the message's content; pure, tested.
 */
data class ActionCard(val summary: String, val ok: Boolean, val undo: ToolBox.Undo?, val undone: Boolean = false) {
    fun encode(): String = JSONObject().put("s", summary).put("ok", ok).put("undone", undone).apply {
        if (undo != null) put("u", JSONObject().put("k", undo.kind).put("id", undo.id))
    }.toString()

    companion object {
        const val ROLE = "action"
        fun decode(content: String): ActionCard? = runCatching {
            val o = JSONObject(content)
            ActionCard(
                o.getString("s"), o.optBoolean("ok", true),
                o.optJSONObject("u")?.let { ToolBox.Undo(it.getString("k"), it.getString("id")) },
                o.optBoolean("undone", false),
            )
        }.getOrNull()
    }
}
