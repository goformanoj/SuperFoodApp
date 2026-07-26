package com.jarvis.os.control

/**
 * Parses screen-control command markers out of an AI reply. The model emits,
 * only when the user clearly asks, lines like:
 *   <<OPEN|Instagram>>        open an app by name
 *   <<TAP|Like>>              tap a currently-visible control by its label
 * To open an app and tap something inside it, the model emits both. For v1 we
 * take at most one of each. Returns the reply with the markers stripped, plus
 * the parsed plan.
 */
object ScreenActions {

    private val OPEN = Regex("""<<OPEN\|([^>]*)>>""")
    private val TAP = Regex("""<<TAP\|([^>]*)>>""")

    data class Plan(val clean: String, val openApp: String?, val tapLabel: String?) {
        val hasAction: Boolean get() = openApp != null || tapLabel != null
    }

    fun parse(reply: String): Plan {
        var clean = reply
        val openApp = OPEN.find(reply)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val tapLabel = TAP.find(reply)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        clean = clean.replace(OPEN, "").replace(TAP, "").trim()
        return Plan(clean, openApp, tapLabel)
    }
}
