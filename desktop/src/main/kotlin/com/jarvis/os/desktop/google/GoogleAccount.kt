package com.jarvis.os.desktop.google

import com.jarvis.os.desktop.AppDirs
import com.jarvis.os.desktop.GoogleSignIn
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * The user's Google Calendar + Gmail connection on this laptop (AGENT_PLAN Phase 6).
 *
 * Connecting runs Google's own consent page (loopback + PKCE, like sign-in) for exactly
 * these scopes: calendar events, reading mail, writing drafts (and sending them — which
 * the app only does after the user clicks Approve). The long-lived refresh token is sealed
 * with Windows DPAPI, so only this Windows user on this machine can read it; it never
 * leaves the laptop and never goes to the Worker. Access tokens live in memory only.
 */
class GoogleAccount(
    private val file: File = AppDirs.file("google.bin"),
    private val vault: Vault = Dpapi,
) {
    /** Seals secrets at rest. Production: DPAPI (per Windows user). Tests: a stand-in. */
    interface Vault {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    object Dpapi : Vault {
        override fun seal(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
        override fun open(sealed: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(sealed)
    }

    data class Stored(val email: String?, val refreshToken: String, val scope: String)

    private var cached: Stored? = null
    private var accessToken: String? = null
    private var accessExpires = 0L
    private val lock = Mutex()

    fun stored(): Stored? = cached ?: runCatching {
        if (!file.isFile) return null
        val o = JSONObject(String(vault.open(file.readBytes()), Charsets.UTF_8))
        Stored(o.optString("email").ifBlank { null }, o.getString("refresh_token"), o.optString("scope"))
    }.getOrNull().also { cached = it }

    val connected: Boolean get() = stored() != null
    val email: String? get() = stored()?.email
    fun has(scope: String): Boolean = stored()?.scope?.split(' ')?.contains(scope) == true

    /** Opens Google's consent page and keeps the result. Returns the token response (its id_token can also link the account). */
    suspend fun connect(): String {
        val body = GoogleSignIn.authorize(SCOPES.joinToString(" "), offline = true)
        save(body, now = System.currentTimeMillis())
        return body
    }

    /** Keeps what a token response grants. Pure except for the file; tested with a stand-in vault. */
    fun save(tokenResponse: String, now: Long) {
        val o = JSONObject(tokenResponse)
        val refresh = o.optString("refresh_token").ifBlank { null }
            ?: throw IllegalStateException("Google didn't grant lasting access. Try Connect again and allow all the boxes.")
        val stored = Stored(emailFromIdToken(o.optString("id_token")), refresh, o.optString("scope"))
        file.parentFile?.mkdirs()
        file.writeBytes(vault.seal(JSONObject().put("email", stored.email).put("refresh_token", stored.refreshToken).put("scope", stored.scope).toString().toByteArray(Charsets.UTF_8)))
        cached = stored
        remember(o, now)
    }

    /** A valid access token, refreshed when it's about to expire. */
    suspend fun accessToken(): String = lock.withLock {
        val now = System.currentTimeMillis()
        accessToken?.takeIf { now < accessExpires - 60_000 }?.let { return it }
        val s = stored() ?: throw IllegalStateException("Google isn't connected — connect it in Settings.")
        val body = withContext(Dispatchers.IO) { GoogleSignIn.refresh(s.refreshToken) }
        remember(JSONObject(body), now)
        accessToken ?: throw IllegalStateException("Google didn't return an access token.")
    }

    /** Forgets the connection here and asks Google to revoke it. */
    suspend fun disconnect() {
        val s = stored()
        cached = null; accessToken = null; accessExpires = 0
        file.delete()
        if (s != null) withContext(Dispatchers.IO) { GoogleSignIn.revoke(s.refreshToken) }
    }

    private fun remember(o: JSONObject, now: Long) {
        o.optString("access_token").ifBlank { null }?.let {
            accessToken = it
            accessExpires = now + o.optLong("expires_in", 3600) * 1000
        }
    }

    companion object {
        const val CALENDAR = "https://www.googleapis.com/auth/calendar.events"
        const val GMAIL_READ = "https://www.googleapis.com/auth/gmail.readonly"
        const val GMAIL_COMPOSE = "https://www.googleapis.com/auth/gmail.compose"
        val SCOPES = listOf("openid", "email", CALENDAR, GMAIL_READ, GMAIL_COMPOSE)

        /** The email inside a Google ID token (its payload is plain base64url JSON). Pure; tested. */
        fun emailFromIdToken(idToken: String): String? = runCatching {
            val payload = idToken.split('.')[1]
            JSONObject(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')), Charsets.UTF_8))
                .optString("email").ifBlank { null }
        }.getOrNull()
    }
}
