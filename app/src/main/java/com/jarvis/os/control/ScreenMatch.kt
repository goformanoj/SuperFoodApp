package com.jarvis.os.control

/**
 * Pure label-matching and redaction logic for screen control, lifted out of
 * [ScreenControlService] so it runs on the JVM with no Android runtime — the
 * "tap the right control" scoring and the credential redaction are exactly the
 * accuracy-critical bits that used to be verifiable only on a device.
 *
 * Nothing here touches `AccessibilityNodeInfo` or any Android API: callers read
 * a node's text / content-description / password flag and pass plain strings in.
 * The scores are calibrated against [ScreenControlService]'s `GOOD_SCORE`
 * threshold, so changing the numbers here changes tap behaviour there.
 */
internal object ScreenMatch {

    /** Bare 4-8 digit runs — one-time codes — are masked before leaving the device. */
    private val OTP_LIKE = Regex("""\b\d{4,8}\b""")

    /** Double / smart double quotes. Apostrophes are NOT here — "O'Brien" must survive. */
    private val QUOTES = setOf('"', '“', '”')

    /**
     * Clean a tap LABEL before it is matched to a control.
     *
     * The model routinely appends the value it is acting on to the control name —
     * a device trace had `<<TAP|Search "milk">>`, and no control is called
     * `search "milk"`, so the tap found nothing and the errand went in circles.
     * The real control is just "Search". So: unwrap a fully-quoted label
     * (`"Search"` → `Search`) and drop a trailing quoted argument
     * (`Search "milk"` → `Search`). Only double quotes trigger this, never an
     * apostrophe, so a genuine label keeps its punctuation. Conservative on
     * purpose: it removes only what is unambiguously an appended argument, so a
     * plain label like "Add to cart" is returned untouched.
     */
    fun normalizeLabel(label: String): String {
        var s = label.trim()
        if (s.length >= 2 && s.first() in QUOTES && s.last() in QUOTES) {
            s = s.substring(1, s.length - 1).trim()
        }
        val firstQuote = s.indexOfFirst { it in QUOTES }
        if (firstQuote > 0) s = s.substring(0, firstQuote).trim()
        return s
    }

    /**
     * Best score for a control whose visible text is [text] and content-description
     * is [desc], matched against an already-normalised [query]. Higher = better;
     * visible text beats content-description. [text] and [desc] are normalised here
     * (trim + lowercase), so raw node strings can be passed straight in.
     */
    fun matchScore(text: String, desc: String, query: String): Int {
        val t = text.trim().lowercase()
        val d = desc.trim().lowercase()
        return maxOf(fieldScore(t, query, isText = true), fieldScore(d, query, isText = false))
    }

    /** Score one field ([value], already normalised) against [query]. Exact beats partial. */
    fun fieldScore(value: String, query: String, isText: Boolean): Int {
        if (value.isEmpty()) return 0
        return when {
            value == query -> if (isText) 100 else 85
            startsWithWord(value, query) -> if (isText) 90 else 60
            containsWord(value, query) -> if (isText) 65 else 45
            value.contains(query) -> if (isText) 55 else 35
            else -> 0
        }
    }

    /** [s] starts with [q] followed by a word boundary (so "mom" matches "mom (dad)" but not "mom's status"). */
    fun startsWithWord(s: String, q: String): Boolean {
        if (!s.startsWith(q)) return false
        if (s.length == q.length) return true
        val next = s[q.length]
        return !next.isLetterOrDigit() && next != '\''
    }

    /** [q] appears in [s] as a standalone word. */
    fun containsWord(s: String, q: String): Boolean {
        var idx = s.indexOf(q)
        while (idx >= 0) {
            val before = if (idx == 0) ' ' else s[idx - 1]
            val afterIdx = idx + q.length
            val after = if (afterIdx >= s.length) ' ' else s[afterIdx]
            if (!before.isLetterOrDigit() && before != '\'' && !after.isLetterOrDigit() && after != '\'') {
                return true
            }
            idx = s.indexOf(q, idx + 1)
        }
        return false
    }

    /**
     * Screen text goes to a third-party model, so credentials must never travel
     * with it. Password fields are replaced wholesale, and bare 4-8 digit runs
     * (one-time codes) are masked wherever they appear.
     */
    fun redactSensitive(text: String, isPassword: Boolean): String {
        if (isPassword) return "***"
        return text.replace(OTP_LIKE, "***")
    }
}
