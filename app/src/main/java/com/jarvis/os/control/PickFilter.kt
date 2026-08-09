package com.jarvis.os.control

/**
 * Keeps the content options in front of the `<<PICK>>` chooser and the navigation
 * chrome out of it.
 *
 * From a device trace: "tell me the best reel here" put `<<PICK|the top Reel>>` to
 * the chooser, whose option list — every clickable label on screen, in reading
 * order — included the bottom-nav tab literally named "Reels". The small chooser
 * model matched the description "the top Reel" to the string "Reels" and JARVIS
 * opened the Reels tab instead of playing a reel. The label of a nav tab and the
 * thing the user wants are indistinguishable as bare strings, so the fix is to drop
 * the known navigation labels before the model ever sees them.
 *
 * Never empties the list: if EVERYTHING on screen is navigation (a home screen, a
 * tab bar with nothing else), the chooser still gets the full set rather than
 * nothing to pick from. This only ever removes chrome when real content remains,
 * so it can help the choice and cannot break one that was already the only option.
 *
 * Pure and case-insensitive, so it is unit-tested directly — the model round-trip
 * lives in [Brain.choose].
 */
object PickFilter {

    /**
     * Bottom-bar / top-bar navigation destinations across the apps JARVIS drives
     * (YouTube, Instagram, Facebook, Spotify, shopping apps). These are wholesale
     * destinations, never the specific item a `<<PICK>>` is after.
     */
    private val NAV_LABELS = setOf(
        "home", "search", "explore", "discover", "shorts", "short", "reels", "reel",
        "stories", "story", "profile", "you", "notifications", "menu", "more",
        "feed", "following", "for you", "subscriptions", "library", "inbox",
        "activity", "shop", "marketplace", "groups", "friends", "watch", "settings",
        "account", "cart", "back", "upgrade",
    )

    /** The options worth choosing from: content first, chrome dropped when content remains. */
    fun preferContent(options: List<String>): List<String> {
        val content = options.filterNot { normalise(it) in NAV_LABELS }
        return if (content.isNotEmpty()) content else options
    }

    private fun normalise(label: String): String = label.trim().lowercase()
}
