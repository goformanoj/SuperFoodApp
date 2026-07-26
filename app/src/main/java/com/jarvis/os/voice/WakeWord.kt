package com.jarvis.os.voice

/** Shared "Hey JARVIS" wake-word matching, used in-app and by the background service. */
object WakeWord {

    // Includes common speech-to-text mishearings of "JARVIS".
    private val WORDS = listOf(
        "hey jarvis", "hey, jarvis", "hi jarvis", "hello jarvis",
        "ok jarvis", "okay jarvis", "hey jervis", "hey travis", "hey jarvis,",
    )

    /** The command after the wake word (may be blank), or null if no wake word present. */
    fun extractCommand(raw: String): String? {
        val lower = raw.lowercase()
        for (w in WORDS) {
            val idx = lower.indexOf(w)
            if (idx >= 0) {
                return raw.substring(idx + w.length).trimStart(' ', ',', '.', '!', '?')
            }
        }
        return null
    }

    fun detect(raw: String): Boolean = extractCommand(raw) != null
}
