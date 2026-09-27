package com.jarvis.os.ai

import java.util.Locale

/**
 * Desktop half of the `UsageStats` seam: today's token use, as reported by the
 * Worker on every reply ([ProxyClient] calls [record]). In memory only — the Worker
 * is the source of truth and re-reports on the next turn.
 *
 * Caps mirror backend/src/quota.js, like the phone's copy — keep in step.
 */
object UsageStats {

    const val FREE_DAILY_TOKENS = 60_000
    const val PRO_DAILY_TOKENS = 2_000_000

    data class Daily(val plan: String, val used: Int, val cap: Int) {
        val remaining: Int get() = (cap - used).coerceAtLeast(0)
    }

    @Volatile private var latest: Daily? = null

    fun capFor(plan: String?): Int = if (plan == "pro") PRO_DAILY_TOKENS else FREE_DAILY_TOKENS

    fun usedFrom(cap: Int, remaining: Int): Int = (cap - remaining).coerceIn(0, cap)

    fun format(n: Int): String = "%,d".format(Locale.US, n)

    fun record(plan: String?, remaining: Int, @Suppress("UNUSED_PARAMETER") nowMs: Long = System.currentTimeMillis()) {
        val p = plan ?: latest?.plan ?: "free"
        val cap = capFor(p)
        latest = Daily(p, usedFrom(cap, remaining), cap)
    }

    fun today(): Daily? = latest
}
