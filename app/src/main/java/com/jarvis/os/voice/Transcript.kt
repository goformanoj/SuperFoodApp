package com.jarvis.os.voice

/**
 * Turns the speech recogniser's ranked guesses into a short hint the model can
 * use to recover from a mishearing.
 *
 * Android's [android.speech.SpeechRecognizer] returns an n-best list — its best
 * guess first, then the alternatives it also considered for the same audio
 * ("pic", "peak", "pick"). The app used to keep only the first and throw the
 * rest away, so when the top guess was wrong the brain never saw the word the
 * user actually said. This keeps the near misses and, when an alternative
 * differs from the best guess by only a word or two, surfaces those words so the
 * model can pick the reading that fits the conversation and the user's known
 * names.
 *
 * Only whole different words are offered, never a wholesale different sentence:
 * a lower-ranked guess with a different number of words is a different guess,
 * not a swapped word, and surfacing it would only add noise.
 *
 * Pure text in, pure text out — no Android types — so it is unit-tested directly.
 */
object Transcript {

    /** At most this many alternative words are ever offered, to stay terse. */
    private const val MAX_SUGGESTIONS = 4

    private val PUNCTUATION = charArrayOf(',', '.', '!', '?', ';', ':', '"', '\'')

    /**
     * The alternative words worth offering the model, given the recogniser's
     * ranked [hypotheses] (best first).
     *
     * Empty when the guesses agree, or differ too wholesale to be a single
     * mishearing. Order follows the recogniser's ranking, de-duplicated.
     */
    fun alternativeWords(hypotheses: List<String>): List<String> {
        val rows = hypotheses
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { it.split(Regex("\\s+")) }
        if (rows.size < 2) return emptyList()

        val best = rows.first()
        val out = LinkedHashSet<String>()
        for (alt in rows.drop(1)) {
            // A different number of words is a different sentence, not one
            // swapped word — skip it rather than surface unrelated guesses.
            if (alt.size != best.size) continue
            val diffs = best.indices.filter { !best[it].equals(alt[it], ignoreCase = true) }
            // Nothing differs, or too much does to be a single misheard word.
            if (diffs.isEmpty() || diffs.size > 2) continue
            for (i in diffs) {
                val candidate = alt[i].trim(*PUNCTUATION)
                val against = best[i].trim(*PUNCTUATION)
                if (candidate.length >= 2 && candidate.any { it.isLetter() } && confusable(against, candidate)) {
                    out.add(candidate)
                }
            }
        }
        return out.take(MAX_SUGGESTIONS).toList()
    }

    /**
     * A one-line note for the model's context — the alternatives quoted — or
     * null when there is nothing useful to add.
     */
    fun disambiguationHint(hypotheses: List<String>): String? {
        val words = alternativeWords(hypotheses)
        if (words.isEmpty()) return null
        return words.joinToString(", ") { "\"$it\"" }
    }

    /**
     * Whether two words are a plausible mishearing of each other.
     *
     * The recogniser already offered both for the same audio, so the bar is
     * deliberately low: a shared first letter or a couple of edits is enough.
     * The point is only to drop the rare unrelated alignment, not to judge
     * phonetics precisely.
     */
    private fun confusable(a: String, b: String): Boolean {
        val x = a.lowercase().trim(*PUNCTUATION)
        val y = b.lowercase().trim(*PUNCTUATION)
        if (x.isEmpty() || y.isEmpty() || x == y) return false
        if (x.first() == y.first()) return true
        return levenshtein(x, y) <= 2
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
