package com.jarvis.os.ai

import com.jarvis.os.BuildConfig
import com.jarvis.os.debug.DebugLog
import com.jarvis.os.desktop.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

/** Thrown with a short, safe (no key/token) reason when identity cannot be obtained. */
class IdentityException(message: String) : Exception(message)

/**
 * Desktop half of the `Identity` seam: an anonymous Firebase identity over the Auth
 * REST API, exactly like the phone's (app/.../ai/Identity.kt) — same endpoints, same
 * token caching, same "refresh, else sign up anew" fallback. It exists as a separate
 * file only because the phone's version persists through Android SharedPreferences;
 * this one persists to a properties file under [AppDirs].
 *
 * It has the same name and the same members [ProxyClient] calls (`isConfigured`,
 * `token`, `forceRefresh`, `cachePlan`), which is what lets the phone's Worker client
 * compile into the desktop unchanged.
 *
 * Google sign-in (account linking) is not here yet — desktop needs a browser
 * loopback OAuth flow, not Credential Manager. Until then the desktop is an
 * anonymous (free-plan) user with its own daily allowance.
 */
object Identity {

    private const val SIGNUP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key="
    private const val REFRESH = "https://securetoken.googleapis.com/v1/token?key="
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_PLAN = "account_plan"

    @Volatile private var idToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L
    @Volatile private var refreshToken: String? = null

    /** Overridable so tests never touch the real profile directory. */
    @Volatile var storeFile: File? = null
    private fun file(): File = storeFile ?: AppDirs.file("identity.properties")

    internal data class TokenSet(val idToken: String, val refreshToken: String, val expiresInSec: Long)

    fun isConfigured(): Boolean = BuildConfig.FIREBASE_API_KEY.isNotBlank()

    /** The plan the Worker last metered us at ("free" until told otherwise). */
    fun plan(): String = load().getProperty(KEY_PLAN) ?: "free"

    fun cachePlan(plan: String) = save { it.setProperty(KEY_PLAN, plan) }

    suspend fun token(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // A minute of headroom so a token never expires mid-flight on the Worker.
        idToken?.let { if (now < expiresAtMs - 60_000L) return@withContext it }
        store(fetchFresh(), now)
    }

    suspend fun forceRefresh(): String = withContext(Dispatchers.IO) {
        idToken = null
        store(fetchFresh(), System.currentTimeMillis())
    }

    private fun store(set: TokenSet, now: Long): String {
        idToken = set.idToken
        expiresAtMs = now + set.expiresInSec * 1000L
        if (set.refreshToken != refreshToken) {
            refreshToken = set.refreshToken
            save { it.setProperty(KEY_REFRESH, set.refreshToken) }
        }
        return set.idToken
    }

    private fun fetchFresh(): TokenSet {
        val key = BuildConfig.FIREBASE_API_KEY
        if (key.isBlank()) throw IdentityException("No Firebase API key set")

        val stored = refreshToken ?: load().getProperty(KEY_REFRESH)?.also { refreshToken = it }
        if (stored != null) {
            try {
                return doRefresh(key, stored)
            } catch (e: Exception) {
                // A stale/revoked refresh token is not fatal — start a fresh anonymous
                // identity rather than leaving the app with no brain.
                DebugLog.log(DebugLog.Stage.ERROR, "identity refresh failed, signing up anew")
            }
        }
        return doSignUp(key)
    }

    private fun doSignUp(key: String): TokenSet {
        val (code, body) = post(SIGNUP + key, "application/json", "{\"returnSecureToken\":true}")
        if (code !in 200..299) throw IdentityException("anonymous sign-in failed: HTTP $code")
        return parseSignUp(body)
    }

    private fun doRefresh(key: String, refresh: String): TokenSet {
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
        val (code, body) = post(REFRESH + key, "application/x-www-form-urlencoded", form)
        if (code !in 200..299) throw IdentityException("token refresh failed: HTTP $code")
        return parseRefresh(body)
    }

    private fun post(urlStr: String, contentType: String, payload: String): Pair<Int, String> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", contentType)
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    // identitytoolkit answers camelCase, securetoken snake_case; `expiresIn` is a
    // STRING of seconds in both. Mirrors the phone's tested parsers.
    internal fun parseSignUp(json: String): TokenSet {
        val o = JSONObject(json)
        return TokenSet(
            idToken = o.getString("idToken"),
            refreshToken = o.getString("refreshToken"),
            expiresInSec = o.optString("expiresIn", "3600").toLongOrNull() ?: 3600L,
        )
    }

    internal fun parseRefresh(json: String): TokenSet {
        val o = JSONObject(json)
        return TokenSet(
            idToken = o.getString("id_token"),
            refreshToken = o.getString("refresh_token"),
            expiresInSec = o.optString("expires_in", "3600").toLongOrNull() ?: 3600L,
        )
    }

    @Synchronized
    private fun load(): Properties = Properties().apply {
        val f = file()
        if (f.exists()) runCatching { f.inputStream().use { load(it) } }
    }

    @Synchronized
    private fun save(edit: (Properties) -> Unit) {
        val p = load()
        edit(p)
        runCatching { file().outputStream().use { p.store(it, "JARVIS desktop identity — private, do not share") } }
    }
}
