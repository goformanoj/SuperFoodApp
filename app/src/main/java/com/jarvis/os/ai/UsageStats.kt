package com.jarvis.os.ai

import android.content.Context
import com.jarvis.os.data.Profiles
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Today's token allowance, for showing in the app.
 *
 * The Worker meters tokens per UTC day and returns `remaining` (and `plan`) on every
 * `/chat` reply; [ProxyClient] hands those here after each turn. From the plan we
 * know the daily cap, so `used = cap - remaining`, and the drawer can show "X of Y
 * today" without any extra request. The day key is UTC to match the server's reset,
 * so a stored figure from yesterday is treated as stale rather than shown as today's.
 *
 * The arithmetic is pure and unit-tested; only the persistence needs a context.
 */
object UsageStats {

    // Mirrors backend/src/quota.js — keep in step. A screen-control turn costs many
    // times a chat turn, which is why the cap is on TOKENS, not requests.
    const val FREE_DAILY_TOKENS = 60_000
    const val PRO_DAILY_TOKENS = 2_000_000

    private const val PREFS = "jarvis_usage"
    private const val KEY_DAY = "day"
    private const val KEY_USED = "used"
    private const val KEY_CAP = "cap"

    @Volatile private var appContext: Context? = null

    // Scoped to the active account, so a guest doesn't see a signed-in user's
    // spend and vice versa; resolved per call to follow a sign-in/out.
    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(Profiles.scoped(PREFS, Identity.profileId()), Context.MODE_PRIVATE)

    data class Daily(val used: Int, val cap: Int) {
        val remaining: Int get() = (cap - used).coerceAtLeast(0)
        val fraction: Float get() = if (cap <= 0) 0f else (used.toFloat() / cap).coerceIn(0f, 1f)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The daily cap for a plan. */
    fun capFor(plan: String?): Int = if (plan == "pro") PRO_DAILY_TOKENS else FREE_DAILY_TOKENS

    /** Tokens spent so far, from the cap and what the server says is left. */
    fun usedFrom(cap: Int, remaining: Int): Int = (cap - remaining).coerceIn(0, cap)

    /** UTC day key, matching the server's reset boundary. */
    fun dayKey(nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(nowMs))
    }

    /** Thousands-separated, so a five/six-figure token count is readable at a glance. */
    fun format(n: Int): String = "%,d".format(Locale.US, n)

    /** Record what the Worker reported after a turn. Best-effort; never throws. */
    fun record(plan: String?, remaining: Int, nowMs: Long = System.currentTimeMillis()) {
        val cap = capFor(plan)
        val used = usedFrom(cap, remaining)
        runCatching {
            prefs()?.edit()
                ?.putString(KEY_DAY, dayKey(nowMs))
                ?.putInt(KEY_USED, used)
                ?.putInt(KEY_CAP, cap)
                ?.apply()
        }
    }

    /** Today's usage, or null when nothing was recorded today (a fresh day/install). */
    fun today(nowMs: Long = System.currentTimeMillis()): Daily? {
        val prefs = prefs() ?: return null
        val day = prefs.getString(KEY_DAY, null) ?: return null
        if (day != dayKey(nowMs)) return null // yesterday's figure — the allowance has reset
        val cap = prefs.getInt(KEY_CAP, FREE_DAILY_TOKENS)
        val used = prefs.getInt(KEY_USED, 0)
        return Daily(used = used, cap = cap)
    }
}
