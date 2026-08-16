package com.jarvis.os.control

/**
 * What a control is actually *called* in a given app.
 *
 * The model reasons in generic intents — "tap Search" — because that is what a
 * search box is. Real apps disagree, at length. A device trace has the plan aiming
 * at `Tap("Search")` in Blinkit, whose search control is labelled
 * **"Search for atta, dal, coke and more"**. Nothing was wrong with the plan: the
 * information needed to write a better one did not exist until the app was open,
 * and even then only as a string nobody could have guessed.
 *
 * So this is a translation table from the generic word to the literal label, keyed
 * by app. It is consulted **only after the requested label has already failed to
 * match confidently**, which is what makes it safe: it can add a way to succeed,
 * never take one away, and an entry that has gone stale (app redesigns happen)
 * simply fails to match like any other wrong guess.
 *
 * The seeded entries below are a starting set, not a database — the durable fix is
 * learning literals from runs that worked, which rides on the same `ok && clean`
 * gate the [com.jarvis.os.assistant.Playbook] already uses. This half ships first
 * because it needs no new state and is verifiable without a device.
 *
 * Pure and unit-tested; the tapping lives in [ScreenControlService].
 */
internal object ControlVocabulary {

    const val SEARCH = "search"
    const val CART = "cart"
    const val ADD = "add"

    /**
     * Generic words the model reaches for, mapped to the intent they express.
     *
     * Deliberately small. A word only belongs here if apps genuinely rename the
     * control it refers to — "Search" is renamed constantly, "Settings" almost
     * never — because every entry is extra work on a path that already failed.
     */
    private val INTENTS: Map<String, String> = mapOf(
        "search" to SEARCH,
        "search bar" to SEARCH,
        "search box" to SEARCH,
        "search field" to SEARCH,
        "find" to SEARCH,
        "cart" to CART,
        "basket" to CART,
        "my cart" to CART,
        "view cart" to CART,
        "add" to ADD,
        "add to cart" to ADD,
        "add item" to ADD,
    )

    /**
     * Literal labels seen in real apps, best-known first.
     *
     * Keyed by a package *fragment* rather than an exact name: shopping apps in
     * particular ship under names that bear no relation to their brand (Blinkit is
     * `com.grofers.customerapp`), and matching a fragment survives the regional and
     * white-label variants that an exact key would miss.
     */
    private val SEEDED: List<Seed> = listOf(
        // Blinkit — the trace that motivated the whole errand loop.
        Seed("grofers", SEARCH, listOf(
            "Search for atta, dal, coke and more",
            "Search \"atta\"",
            "Search for products",
        )),
        Seed("grofers", CART, listOf("My Cart", "Cart")),
        // Zepto — the second failed shopping session.
        Seed("zepto", SEARCH, listOf(
            "Search for over 5000 products",
            "Search for products",
        )),
        Seed("zepto", CART, listOf("Cart")),
        Seed("youtube", SEARCH, listOf("Search YouTube", "Search")),
        Seed("amazon", SEARCH, listOf("Search Amazon.in", "Search Amazon", "Search")),
        Seed("swiggy", SEARCH, listOf("Search for restaurants and food")),
        Seed("zomato", SEARCH, listOf("Restaurant name, cuisine, or a dish...")),
        // Instagram and WhatsApp are deliberately absent: their search controls are
        // labelled "Search" and "Search…", which the existing prefix scoring already
        // matches at 90 — above GOOD_SCORE — so a seed for them would be a dead row
        // that never fires and only implies coverage this table does not have.
    )

    /**
     * Literal labels worth trying for [label] in [pkg], best first, excluding the
     * label already tried. Empty when nothing is known — the caller then behaves
     * exactly as it did before this existed.
     */
    fun candidatesFor(pkg: String?, label: String): List<String> {
        val intent = intentOf(label) ?: return emptyList()
        val app = pkg?.lowercase().orEmpty()
        if (app.isEmpty()) return emptyList()
        val requested = normalise(label)
        return SEEDED
            .filter { it.intent == intent && app.contains(it.packageFragment) }
            .flatMap { it.labels }
            .distinct()
            .filterNot { normalise(it) == requested }
    }

    /** The intent [label] expresses, or null when it is already a specific name. */
    fun intentOf(label: String): String? = INTENTS[normalise(label)]

    /** A generic label is one an app is likely to have renamed. */
    fun isGeneric(label: String): Boolean = intentOf(label) != null

    private data class Seed(
        val packageFragment: String,
        val intent: String,
        val labels: List<String>,
    )

    private fun normalise(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()

}
