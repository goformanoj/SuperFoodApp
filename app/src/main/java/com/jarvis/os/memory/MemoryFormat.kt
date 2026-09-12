package com.jarvis.os.memory

/**
 * How the Memory tab presents what JARVIS has learned.
 *
 * The facts themselves are a flat `List<String>` ([com.jarvis.os.data.UserPreferences.learnedFacts]).
 * A flat list of one-liners is what a debug dump looks like; a memory the user
 * trusts reads as *organised* — grouped by what kind of thing it is, with the
 * obviously private bits (a phone number, an email) blurred so the screen can be
 * held up in a meeting without leaking a contact.
 *
 * All of this is pure string work with no Android on it, so the grouping and the
 * masking are pinned by real JUnit tests rather than confirmed by eye on-device.
 */
object MemoryFormat {

    /** The buckets facts fall into, in the order the screen shows them. */
    enum class Category(val label: String) {
        CONTACT("Contact details"),
        IDENTITY("About you"),
        PEOPLE("People"),
        PLACES("Places & times"),
        PREFERENCES("How you like things"),
        OTHER("Other"),
    }

    data class Group(val category: Category, val facts: List<String>)

    // 5+ run of digits reads as a phone/account number rather than a time or a
    // year; an email is an email. These are the two things worth blurring.
    private val EMAIL = Regex("([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val DIGITS = Regex("\\d{5,}")

    private val PEOPLE = listOf(
        "wife", "husband", "spouse", "partner", "girlfriend", "boyfriend",
        "sister", "brother", "mother", "father", "mom", "dad", "son", "daughter",
        "parents", "kids", "children", "family", "friend", "boss", "manager",
        "colleague", "teammate",
    )
    private val PLACES = listOf(
        "live", "lives", "living", "city", "address", "timezone", "time zone",
        "located", "based in", "ist", "pst", "est", "gmt", "utc", "country",
    )
    private val IDENTITY = listOf(
        "call me", "address me", "my name", "i am ", "i'm ", "i work", "my job",
        "my birthday", "i was born", "pronoun", "allerg",
    )
    private val PREFERENCES = listOf(
        "prefer", "like", "dislike", "hate", "love", "favourite", "favorite",
        "always", "never", "keep answers", "short answer", "tone", "brief",
        "concise", "formal", "casual",
    )

    /** True when [fact] carries something worth blurring on a shared screen. */
    fun isSensitive(fact: String): Boolean = masked(fact) != fact

    /**
     * Blurs emails and long digit runs, keeping just enough to recognise which
     * fact it is. Everything else is returned untouched.
     */
    fun masked(fact: String): String {
        var s = EMAIL.replace(fact) { m ->
            "${m.groupValues[1].take(1)}•••@${m.groupValues[2]}"
        }
        s = DIGITS.replace(s) { m ->
            val d = m.value
            "•".repeat(d.length - 3) + d.takeLast(3)
        }
        return s
    }

    /** Which bucket a single fact belongs to. First match wins, most-specific first. */
    fun categorize(fact: String): Category {
        if (isSensitive(fact)) return Category.CONTACT
        val lower = fact.lowercase()
        fun any(words: List<String>) = words.any { lower.contains(it) }
        return when {
            any(PEOPLE) -> Category.PEOPLE
            any(PLACES) -> Category.PLACES
            any(IDENTITY) -> Category.IDENTITY
            any(PREFERENCES) -> Category.PREFERENCES
            else -> Category.OTHER
        }
    }

    /**
     * Groups [facts] into the non-empty categories, in [Category] order, keeping
     * each fact's original position within its group so the newest stays last.
     */
    fun grouped(facts: List<String>): List<Group> =
        Category.entries.mapNotNull { cat ->
            facts.filter { categorize(it) == cat }.takeIf { it.isNotEmpty() }
                ?.let { Group(cat, it) }
        }
}
