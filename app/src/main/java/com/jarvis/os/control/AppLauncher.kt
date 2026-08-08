package com.jarvis.os.control

import android.content.Context
import android.content.Intent

/**
 * Resolves a spoken app name (e.g. "WhatsApp", "Amazon Music") to an installed
 * launchable app and starts it. Needs QUERY_ALL_PACKAGES to see other apps'
 * launcher entries on Android 11+. Returns the launched package name, or null if
 * nothing matched or it couldn't be launched.
 *
 * The matching used to take the FIRST app whose label loosely contained the query,
 * which is how "Amazon Music" opened plain "Amazon" (the shop) or "Music": the
 * query contains the shorter label, so the shorter, more general app matched
 * first. Now every candidate is scored and the best one wins — an app that covers
 * more of what was said beats a shorter, vaguer match ([rank]).
 */
object AppLauncher {

    fun launch(context: Context, name: String): String? {
        val pm = context.packageManager
        val target = resolvePackage(context, name) ?: return null
        val intent = pm.getLaunchIntentForPackage(target) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Best matching launchable package for [name], or null. */
    fun resolvePackage(context: Context, name: String): String? {
        val query = name.trim()
        if (query.isEmpty()) return null

        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)

        val entries = apps.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null // never target ourselves
            pkg to info.loadLabel(pm).toString()
        }
        val idx = rank(query, entries.map { it.second }) ?: return null
        return entries[idx].first
    }

    /**
     * The index of the best-matching label for [query], or null if none is a
     * plausible match. Pure and case-insensitive, so it is unit-tested directly.
     *
     * Scoring, highest first: an exact name; then how many of the spoken words the
     * label actually contains (so "Amazon Music" beats "Amazon" for "amazon
     * music"), with a bonus for covering ALL of them and a small penalty for extra
     * words the label adds (so the tightest full match wins).
     */
    fun rank(query: String, labels: List<String>): Int? {
        val q = query.lowercase().trim()
        val qTokens = tokenize(q).filter { it !in STOPWORDS }
        var bestIdx: Int? = null
        var bestScore = 0
        labels.forEachIndexed { i, raw ->
            val s = score(q, qTokens, raw.lowercase().trim())
            if (s > bestScore) {
                bestScore = s
                bestIdx = i
            }
        }
        return if (bestScore >= MIN_SCORE) bestIdx else null
    }

    private fun score(query: String, qTokens: List<String>, label: String): Int {
        if (label.isEmpty()) return 0
        if (label == query) return 1000
        if (qTokens.isEmpty()) return 0

        val lTokens = tokenize(label)
        if (lTokens.isEmpty()) return 0

        val matched = qTokens.count { qt -> lTokens.any { tokensMatch(qt, it) } }
        if (matched == 0) return 0

        var s = matched * 100
        if (matched == qTokens.size) s += 200 // covered everything that was said
        // Each label word unrelated to the query makes it a looser fit.
        val extra = lTokens.count { lt -> qTokens.none { qt -> tokensMatch(qt, lt) } }
        s -= extra * 10
        return s
    }

    /** Equal, or one is a prefix of the other (min 3 chars) — catches "insta"/"instagram". */
    private fun tokensMatch(a: String, b: String): Boolean =
        a == b ||
            (a.length >= 3 && b.startsWith(a)) ||
            (b.length >= 3 && a.startsWith(b))

    private fun tokenize(s: String): List<String> =
        s.split(NON_ALNUM).filter { it.isNotEmpty() }

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    // Filler words that shouldn't drive app matching.
    private val STOPWORDS = setOf("app", "application", "the")

    /** At least one solid word match; below this, resolve to nothing rather than a wrong app. */
    private const val MIN_SCORE = 90
}
