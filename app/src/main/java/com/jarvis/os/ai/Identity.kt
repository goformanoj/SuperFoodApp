package com.jarvis.os.ai

import android.content.Context
import com.jarvis.os.BuildConfig
import com.jarvis.os.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Thrown with a short, safe (no key/token) reason when identity cannot be obtained. */
class IdentityException(message: String) : Exception(message)

/**
 * Anonymous Firebase identity, over the Auth REST API — deliberately WITHOUT the
 * Firebase SDK.
 *
 * ## Why REST, not the SDK
 *
 * The Worker (Phase 3) now trusts a uid only when it arrives as a signed Firebase
 * ID token. The app needs one such token per call. The SDK is the usual way to get
 * it, but it drags in the `google-services` Gradle plugin (which fails the build
 * outright without a `google-services.json`) and a `google-services.json` carrying
 * the project's API key — a file this project injects secrets rather than commits.
 * The SDK ultimately just calls the same two REST endpoints used here, and for an
 * anonymous token that is all we need. Google sign-in / account linking (Phase 6,
 * subscriptions) is the one thing this cannot do, and is not needed until then.
 *
 * The public Web API key ([BuildConfig.FIREBASE_API_KEY]) is the only input, and
 * the token it mints was already proven to verify against the live Worker.
 *
 * ## Shape
 *
 * `token()` returns a valid ID token, minting or refreshing as needed and caching
 * it in memory. The refresh token is persisted (so the SAME anonymous uid — and so
 * the same daily quota — survives an app restart) once [init] has supplied an app
 * context; before that it degrades to in-memory only, which is enough for a probe
 * like Diagnostics. The pure response parsers are [parseSignUp]/[parseRefresh],
 * tested off-device.
 */
object Identity {

    private const val SIGNUP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key="
    private const val REFRESH = "https://securetoken.googleapis.com/v1/token?key="
    private const val PREFS = "jarvis_identity"
    private const val KEY_REFRESH = "refresh_token"

    /** App context for persisting the refresh token. Null until [init]. */
    @Volatile private var appContext: Context? = null

    // In-memory cache of the current token and when it lapses.
    @Volatile private var idToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    /** Persisted so a restart keeps the same anonymous identity. Cached to avoid disk reads. */
    @Volatile private var refreshToken: String? = null

    /** Called once from MainActivity so the identity can outlive the process. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isConfigured(): Boolean = BuildConfig.FIREBASE_API_KEY.isNotBlank()

    internal data class TokenSet(val idToken: String, val refreshToken: String, val expiresInSec: Long)

    /**
     * A valid Firebase ID token, minting or refreshing as needed. Refresh is tried
     * before a fresh anonymous sign-up so the uid stays stable across the ~1h token
     * lifetime and across restarts.
     */
    suspend fun token(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // A minute of headroom so a token never expires mid-flight on the Worker.
        idToken?.let { if (now < expiresAtMs - 60_000L) return@withContext it }
        store(fetchFresh(), now)
    }

    /**
     * Force a new token even if the cached one looks valid. Used when the Worker
     * rejects a token (clock skew, rotation) so the caller can retry once.
     */
    suspend fun forceRefresh(): String = withContext(Dispatchers.IO) {
        idToken = null
        store(fetchFresh(), System.currentTimeMillis())
    }

    /** Store a freshly obtained token set in memory + on disk, and return the id token. */
    private fun store(set: TokenSet, now: Long): String {
        idToken = set.idToken
        expiresAtMs = now + set.expiresInSec * 1000L
        if (set.refreshToken != refreshToken) {
            refreshToken = set.refreshToken
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_REFRESH, set.refreshToken)?.apply()
        }
        return set.idToken
    }

    /** Refresh with a stored token if there is one; otherwise sign up anonymously. */
    private fun fetchFresh(): TokenSet {
        val key = BuildConfig.FIREBASE_API_KEY
        if (key.isBlank()) throw IdentityException("No Firebase API key set")

        val stored = refreshToken ?: appContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_REFRESH, null)
            ?.also { refreshToken = it }

        if (stored != null) {
            try {
                return doRefresh(key, stored)
            } catch (e: Exception) {
                // A stale/revoked refresh token is not fatal — fall back to a fresh
                // anonymous identity rather than leaving the app with no brain.
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

    /** POST and return (status, body). Body is read from the error stream on failure. */
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

    // The two endpoints answer in different casing — identitytoolkit is camelCase,
    // securetoken is snake_case — so they are parsed separately. Both are pure and
    // tested. `expiresIn` is a STRING of seconds in both.
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
}
