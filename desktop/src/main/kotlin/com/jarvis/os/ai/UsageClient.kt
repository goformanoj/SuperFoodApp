package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the Worker "who am I and what's left today" (GET /usage) — no model call, no tokens spent.
 *
 * Why it exists: the plan and the allowance used to arrive only on the reply to a MESSAGE, so right after
 * signing in (or at launch) the sidebar said "Free · usage shows after a reply" for an account that is Pro.
 * Now the app asks as soon as it knows who is signed in.
 */
object UsageClient {

    data class Snapshot(val plan: String, val cap: Int, val remaining: Int)

    /** Pure; tested. Null for anything that is not a well-formed answer, so a bad reply never overwrites good state. */
    fun parse(json: String): Snapshot? = try {
        val o = JSONObject(json)
        val plan = o.optString("plan").ifBlank { null }
        if (plan == null || !o.has("remaining") || !o.has("cap")) null
        else Snapshot(plan, o.getInt("cap"), o.getInt("remaining"))
    } catch (e: Exception) {
        null
    }

    /** Fetches the snapshot and records it (plan cache + usage meter). Null if the Worker can't be reached. */
    suspend fun refresh(): Snapshot? = withContext(Dispatchers.IO) {
        if (!ProxyClient.isConfigured()) return@withContext null
        var res = get(Identity.token())
        if (res.first == 401) res = get(Identity.forceRefresh())
        val snap = if (res.first in 200..299) parse(res.second) else null
        snap?.let {
            Identity.cachePlan(it.plan)
            UsageStats.record(it.plan, it.remaining)
        }
        snap
    }

    private fun get(token: String): Pair<Int, String> {
        val conn = URL(BuildConfig.WORKER_URL.trimEnd('/') + "/usage").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("X-Proxy-Secret", BuildConfig.PROXY_SECRET)
        conn.setRequestProperty("Authorization", "Bearer $token")
        return try {
            val code = conn.responseCode
            code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            0 to ""
        } finally {
            conn.disconnect()
        }
    }
}
