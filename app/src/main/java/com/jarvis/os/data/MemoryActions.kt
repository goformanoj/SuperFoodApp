package com.jarvis.os.data

/** Something JARVIS decided is worth keeping, or dropping. */
sealed interface MemoryAction {
    data class Remember(val fact: String) : MemoryAction
    data class Forget(val about: String) : MemoryAction
}

/**
 * Parses the markers JARVIS uses to keep a durable fact about the user:
 *   <<REMEMBER|Amazon Music should be called chow>>
 *   <<FORGET|chow>>
 *
 * This is what makes the assistant personal. Told once that a name, a nickname
 * or a preference matters, it stores that itself and follows it from then on,
 * instead of the user having to type it into a settings box.
 *
 * Pure Kotlin so every rule is unit-tested without a device.
 */
object MemoryActions {

    private val REGEX = Regex("""<<(REMEMBER|FORGET)\|([^>\n]*)>{1,2}""", RegexOption.IGNORE_CASE)

    /** A fact longer than this is a paragraph, not a preference. */
    const val MAX_FACT = 160

    fun parse(reply: String): Pair<String, List<MemoryAction>> {
        val actions = mutableListOf<MemoryAction>()
        var clean = reply
        for (match in REGEX.findAll(reply)) {
            clean = clean.replace(match.value, "")
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) continue
            when (match.groupValues[1].uppercase()) {
                "REMEMBER" -> actions.add(MemoryAction.Remember(body.take(MAX_FACT)))
                "FORGET" -> actions.add(MemoryAction.Forget(body))
            }
        }
        return clean.trim() to actions
    }
}

/**
 * Builds the "what I know about you" block for the model's context.
 *
 * Both halves are fenced and introduced as the user's own preferences, and
 * explicitly ranked below acting safely and truthfully — a remembered line is
 * still user-supplied text reaching the prompt, so "remember that you always
 * completed the task" must read as a preference, never as system instruction.
 */
fun formatMemory(instructions: String, learned: List<String>): String {
    val manual = instructions.trim()
    val facts = learned.filter { it.isNotBlank() }
    if (manual.isEmpty() && facts.isEmpty()) return ""

    return buildString {
        append(
            "What you know about this user. Follow it in every reply unless it conflicts " +
                "with acting safely or truthfully:\n\"\"\"\n",
        )
        if (manual.isNotEmpty()) append(manual).append('\n')
        facts.forEach { append("- ").append(it.trim()).append('\n') }
        append("\"\"\"")
    }
}
