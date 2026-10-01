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
        // A call that already failed once THIS turn, repeated with the identical arguments,
        // is never going to succeed the second time — it just burns the step budget until
        // the turn stalls (found live: a failed open_file on a path that doesn't exist got
        // retried twice before giving up). Caught in code (Rule 6), not left to the prompt:
        // the retry is short-circuited with a message telling the model plainly why, so it
        // spends its remaining steps trying something else or just reporting what it has.
        val failedBefore = mutableSetOf<Pair<String, String>>()
        // Not every unproductive loop repeats the SAME call OR even fails — found live: after
        // one open_file failure, the model called search_files several MORE times (each one
        // succeeding — it's a READ tool, there's always something to return) hoping for a
        // better result it was never going to get, instead of trusting what it already had and
        // reporting. So two signals both earn a nudge to wrap up, success or failure: several
        // real failures this turn (not merely repeats of one — see above), or the same tool
        // called repeatedly regardless of outcome. A turn with few calls, or varied successful
        // ones, never triggers either.
        var failureCount = 0
        val callCounts = mutableMapOf<String, Int>()
        repeat(maxSteps) {
            val reply = step(messages, context, schemas)
            if (reply.toolCalls.isEmpty()) return reply.text
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
                    runOne(call, sourceConversation)
                }
                if (!result.ok) { failedBefore += key; failureCount++ }
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
        /** How many real failures in one turn before every further failure also says "stop probing, report instead". */
        const val WRAP_UP_AFTER_FAILURES = 2
        /** How many times the SAME tool can be called in one turn before it starts saying "you may already have enough". */
        const val WRAP_UP_AFTER_REPEATS = 3
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
