package com.jarvis.os.desktop.voice

import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.ProxyException
import com.jarvis.os.ai.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a WAV to the Worker's /transcribe (Phase 2) with the same two credentials as
 * /chat — the app secret and a Firebase ID token — and returns the text. The Worker
 * holds the Whisper key and meters the clip against the daily allowance.
 */
object TranscribeClient {

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        var res = post(Identity.token(), wav)
        if (res.first == 401) res = post(Identity.forceRefresh(), wav)
        val (code, body) = res
        if (code !in 200..299) {
            throw ProxyException(
                when (code) {
                    413 -> "That was too long for one go — keep voice commands under two minutes."
                    415, 400 -> "I couldn't make out that recording. Try again."
                    else -> ProxyClient.errorMessage(code, body)
                },
            )
        }
        val o = JSONObject(body)
        val plan = o.optString("plan").ifBlank { null }
        if (plan != null) Identity.cachePlan(plan)
        if (o.has("remaining")) UsageStats.record(plan, o.optInt("remaining"))
        o.optString("text").trim()
    }

    private fun post(token: String, wav: ByteArray): Pair<Int, String> {
        val conn = URL(BuildConfig.WORKER_URL.trimEnd('/') + "/transcribe").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 45000
        conn.setRequestProperty("Content-Type", "audio/wav")
        conn.setRequestProperty("X-Proxy-Secret", BuildConfig.PROXY_SECRET)
        conn.setRequestProperty("Authorization", "Bearer $token")
        return try {
            conn.outputStream.use { it.write(wav) }
            val code = conn.responseCode
            code to ((if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            0 to ""
        } finally {
            conn.disconnect()
        }
    }
}
