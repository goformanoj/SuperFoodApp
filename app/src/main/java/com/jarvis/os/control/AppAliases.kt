package com.jarvis.os.control

/**
 * "Cloud means Claude" — turned into a rule the executor obeys, instead of a
 * sentence the model is asked to remember.
 *
 * ## The failure this exists for
 *
 * Speech-to-text hears "Claude" as "Cloud" almost every time. The user wrote
 * `Cloud means Claude` into their custom instructions, which is already sent to
 * the model on every turn, and the reply still carried `<<OPEN|Cloud>>`. On a
 * Realme phone there IS an app labelled "Cloud", so [AppLauncher] scored it an
 * exact match — 1000 out of 1000 — and opened it. Nothing malfunctioned: the
 * executor was handed the wrong name and did the right thing with it, quickly.
 *
 * The prompt has asked for this substitution for months ("when a remembered
 * nickname stands for a real app, put the REAL app name in the marker"). It works
 * most of the time, which is the problem — a rule that holds most of the time is
 * one the user cannot rely on, and they have no way to tell which kind of turn
 * they are getting.
 *
 * So this is [com.jarvis.os.assistant.SendGuard]'s lesson applied one rung down:
 * **when the user has stated a rule in plain words, honour it in code.** The
 * prompt still asks for the substitution — that catches the phrasings this does
 * not — but a nickname the user typed out no longer depends on the model
 * choosing to apply it.
 *
 * ## Why the table is not pre-seeded
 *
 * The tempting fix is a built-in "cloud → Claude" mapping. It would be wrong:
 * "Cloud" is a real, launchable app on this very phone, and on someone else's it
 * may be the one they actually mean. An alias only exists because a particular
 * user said so. Nothing here is global.
 *
 * Pure Kotlin, so the parsing is unit-tested rather than reasoned about.
 */
object AppAliases {

    /**
     * Reads alias rules out of whatever the user has written — custom
     * instructions and [com.jarvis.os.data.UserPreferences.learnedFacts] alike,
     * since `<<REMEMBER>>` stores the same kind of sentence.
     *
     * Returns lowercase spoken name → the name to look up instead. Later lines
     * win, so correcting yourself works the way it reads.
     */
    fun parse(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        lines.forEach { line ->
            // One line can hold more than one rule when separated by sentences.
            line.split(SENTENCE).forEach { clause ->
                val rule = ruleIn(clause) ?: return@forEach
                val (alias, target) = rule
                if (usable(alias) && usable(target) && !alias.equals(target, ignoreCase = true)) {
                    out[alias.lowercase()] = target
                }
            }
        }
        return out
    }

    /**
     * The name to actually look for, after applying [aliases].
     *
     * Falls through unchanged when nothing matches, so this is safe to put in
     * front of every lookup.
     */
    fun resolve(name: String, aliases: Map<String, String>): String {
        if (aliases.isEmpty()) return name
        val key = name.trim().lowercase()
        aliases[key]?.let { return it }
        // "open the cloud app" should hit the same rule as "open cloud".
        val bare = key.removePrefix("the ").removeSuffix(" app").trim()
        return aliases[bare] ?: name
    }

    /**
     * The alias and the real name in one clause, or null.
     *
     * `X is Y` is deliberately absent. It is the most natural phrasing and also
     * the most common sentence shape in an instructions box that has nothing to
     * do with apps — "my name is Manoj", "the office wifi is slow" — and a rule
     * mined out of one of those would silently redirect an app launch. The three
     * kept here are ones nobody writes by accident.
     */
    private fun ruleIn(clause: String): Pair<String, String>? {
        val text = clause.trim()
        if (text.isEmpty()) return null
        WHEN_I_SAY.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        BY_X_I_MEAN.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        X_MEANS_Y.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        X_EQUALS_Y.find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }

    /**
     * A plausible app name: short, wordlike, not a sentence that happened to
     * contain "means".
     */
    private fun usable(raw: String): Boolean {
        val v = raw.trim().trim(*TRIM_CHARS)
        if (v.length !in 2..MAX_NAME_CHARS) return false
        if (v.split(' ').filter { it.isNotBlank() }.size > MAX_NAME_WORDS) return false
        return v.all { it.isLetterOrDigit() || it == ' ' || it == '.' || it == '+' || it == '&' }
    }

    private val WHEN_I_SAY = Regex(
        """when\s+i\s+say\s+(.{2,30}?)\s+i\s+mean\s+(.{2,30}?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val BY_X_I_MEAN = Regex(
        """\bby\s+(.{2,30}?)\s+i\s+mean\s+(.{2,30}?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val X_MEANS_Y = Regex(
        """^\s*(.{2,30}?)\s+means\s+(.{2,30}?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val X_EQUALS_Y = Regex(
        """^\s*(.{2,30}?)\s*=\s*(.{2,30}?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Sentence and list separators, so one line can carry several rules. */
    private val SENTENCE = Regex("""[.;\n]+""")

    private val TRIM_CHARS = charArrayOf(' ', '"', '\'', ',', '.', '!', '?', ':', '-', '—')

    private const val MAX_NAME_CHARS = 30
    private const val MAX_NAME_WORDS = 4
}
