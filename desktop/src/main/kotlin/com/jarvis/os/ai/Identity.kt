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
    private const val IDP = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key="
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_PLAN = "account_plan"
    private const val KEY_EMAIL = "account_email"
    private const val KEY_NAME = "account_name"
    private const val KEY_PROVIDER = "account_provider"

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

    /** Who is signed in on this laptop. Same shape as the phone's `Identity.Account`. */
    data class Account(val email: String?, val name: String?, val isSignedIn: Boolean, val plan: String) {
        val isPro: Boolean get() = plan == "pro"
        val initial: Char
            get() = (name?.trim()?.firstOrNull { it.isLetter() } ?: email?.trim()?.firstOrNull { it.isLetter() })
                ?.uppercaseChar() ?: 'G'
        fun label(): String = name?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() } ?: "Guest"
    }

    fun account(): Account {
        val p = load()
        return Account(
            email = p.getProperty(KEY_EMAIL),
            name = p.getProperty(KEY_NAME),
            isSignedIn = p.getProperty(KEY_PROVIDER) == "google.com",
            plan = p.getProperty(KEY_PLAN) ?: "free",
        )
    }

    /**
     * Signs this laptop in with Google: the ID token from [com.jarvis.os.desktop.GoogleSignIn]
     * goes to Firebase `signInWithIdp`, LINKED onto the current anonymous uid so today's
     * usage carries over — exactly the phone's `linkGoogle`. If that Google account already
     * exists (it does, from the phone), Firebase refuses the link and we sign in AS it,
     * which is the point: the laptop becomes the same account as the phone.
     */
    suspend fun linkGoogle(googleIdToken: String): Account {
        val anon = runCatching { token() }.getOrNull()
        return withContext(Dispatchers.IO) {
            val key = BuildConfig.FIREBASE_API_KEY
            if (key.isBlank()) throw IdentityException("No Firebase API key set")
            var (code, body) = post(IDP + key, "application/json", buildIdpPayload(googleIdToken, anon))
            if (code !in 200..299 && isLinkConflict(code, body)) {
                DebugLog.log(DebugLog.Stage.SESSION, "google account already exists — signing in as it")
                val retry = post(IDP + key, "application/json", buildIdpPayload(googleIdToken, null))
                code = retry.first; body = retry.second
            }
            if (code !in 200..299) throw IdentityException("Google sign-in failed: HTTP $code")
            val res = parseIdp(body)
            store(TokenSet(res.idToken, res.refreshToken, res.expiresInSec), System.currentTimeMillis())
            save {
                it.setProperty(KEY_PROVIDER, "google.com")
                if (res.email != null) it.setProperty(KEY_EMAIL, res.email) else it.remove(KEY_EMAIL)
                if (res.name != null) it.setProperty(KEY_NAME, res.name) else it.remove(KEY_NAME)
                // The plan is re-learned from the Worker on the next reply (owner email → pro).
                it.remove(KEY_PLAN)
            }
            account()
        }
    }

    /** Back to a fresh guest: forget the tokens and who we were. */
    fun signOut() {
        idToken = null
        expiresAtMs = 0L
        refreshToken = null
        save { p -> listOf(KEY_REFRESH, KEY_EMAIL, KEY_NAME, KEY_PROVIDER, KEY_PLAN).forEach { p.remove(it) } }
    }

    internal data class IdpResult(
        val idToken: String,
        val refreshToken: String,
        val expiresInSec: Long,
        val email: String?,
        val name: String?,
    )

    // The three below mirror the phone's tested helpers (app/.../ai/Identity.kt), which
    // cannot be shared directly because that file imports android.content.Context.
    internal fun buildIdpPayload(googleIdToken: String, anonIdToken: String?): String =
        JSONObject().apply {
            put("postBody", "id_token=$googleIdToken&providerId=google.com")
            put("requestUri", "http://localhost")
            put("returnSecureToken", true)
            if (anonIdToken != null) put("idToken", anonIdToken)
        }.toString()

    internal fun parseIdp(json: String): IdpResult {
        val o = JSONObject(json)
        return IdpResult(
            idToken = o.getString("idToken"),
            refreshToken = o.getString("refreshToken"),
            expiresInSec = o.optString("expiresIn", "3600").toLongOrNull() ?: 3600L,
            email = o.optString("email").ifBlank { null },
            name = o.optString("displayName").ifBlank { o.optString("fullName").ifBlank { null } },
        )
    }

    internal fun isLinkConflict(code: Int, json: String): Boolean {
        if (code in 200..299) return false
        val msg = runCatching { JSONObject(json).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
        return msg.contains("FEDERATED_USER_ID_ALREADY_LINKED") || msg.contains("EMAIL_EXISTS") || msg.contains("CREDENTIAL_ALREADY_IN_USE")
    }

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
