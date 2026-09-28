package com.jarvis.os.desktop

import com.jarvis.os.BuildConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.awt.Desktop
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class SignInException(message: String) : Exception(message)

/**
 * Google sign-in for the desktop — the OAuth flow Google prescribes for installed
 * apps: the system browser opens Google's own page, the answer comes back to a
 * one-shot server on 127.0.0.1 (the "loopback" redirect), and PKCE proves the code
 * was requested by this process. JARVIS never sees the user's Google password.
 *
 * The result is a Google ID token, which [com.jarvis.os.ai.Identity.linkGoogle] hands
 * to Firebase — the same step the phone takes after Credential Manager — so the desktop
 * becomes the same account (and the same Pro plan) as the phone.
 *
 * Dormant until a "Desktop app" OAuth client exists: GOOGLE_DESKTOP_CLIENT_ID and
 * GOOGLE_DESKTOP_CLIENT_SECRET in ~/.gradle/gradle.properties. (Google issues a secret
 * to desktop clients but documents that an installed app cannot keep it secret; it is
 * still kept out of the repo like everything else.)
 *
 * The pure pieces (PKCE, the URL, the callback) are unit-tested (GoogleSignInTest).
 */
object GoogleSignIn {

    private const val AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN = "https://oauth2.googleapis.com/token"
    private const val TIMEOUT_MS = 180_000L

    fun isConfigured(): Boolean =
        BuildConfig.GOOGLE_DESKTOP_CLIENT_ID.isNotBlank() && BuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET.isNotBlank()

    /** Opens the browser and returns a Google ID token once the user has signed in. */
    suspend fun signIn(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) throw SignInException("Google sign-in isn't set up on this build yet.")
        val verifier = newVerifier()
        val state = newVerifier().take(24)
        val result = CompletableDeferred<Callback>()

        // Port 0: the OS picks a free one. Bound to loopback only — nothing off this machine can reach it.
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            val cb = parseCallback(ex.requestURI.rawQuery.orEmpty())
            val ok = cb.code != null && cb.state == state
            val bytes = (if (ok) PAGE_OK else PAGE_FAIL).toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            // A favicon request carries neither — keep waiting for the real answer.
            if (cb.code != null || cb.error != null) result.complete(cb)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}"
            val url = authUrl(BuildConfig.GOOGLE_DESKTOP_CLIENT_ID, redirect, challengeFor(verifier), state)
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw SignInException("No browser available to sign in with.")
            }
            Desktop.getDesktop().browse(URI(url))

            val cb = try {
                withTimeout(TIMEOUT_MS) { result.await() }
            } catch (e: Exception) {
                throw SignInException("Sign-in timed out — try again.")
            }
            if (cb.error != null) {
                throw SignInException(if (cb.error == "access_denied") "Sign-in was cancelled." else "Google refused the sign-in (${cb.error}).")
            }
            if (cb.state != state) throw SignInException("Sign-in reply didn't match — try again.")
            exchange(cb.code!!, verifier, redirect)
        } finally {
            server.stop(0)
        }
    }

    private fun exchange(code: String, verifier: String, redirect: String): String {
        val form = listOf(
            "code" to code,
            "client_id" to BuildConfig.GOOGLE_DESKTOP_CLIENT_ID,
            "client_secret" to BuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET,
            "redirect_uri" to redirect,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL(TOKEN).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw SignInException("Google token exchange failed (HTTP $status).")
            return idTokenFrom(body) ?: throw SignInException("Google didn't return an ID token.")
        } finally {
            conn.disconnect()
        }
    }

    // ── Pure, tested ─────────────────────────────────────────────────────────

    data class Callback(val code: String?, val state: String?, val error: String?)

    /** A PKCE code verifier: 43 URL-safe characters from 32 random bytes. */
    fun newVerifier(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** The S256 challenge for [verifier] (RFC 7636 §4.2). */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authUrl(clientId: String, redirect: String, challenge: String, state: String): String {
        val q = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirect,
            "response_type" to "code",
            "scope" to "openid email profile",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "prompt" to "select_account",
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        return "$AUTH?$q"
    }

    fun parseCallback(rawQuery: String): Callback {
        val params = rawQuery.split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        return Callback(params["code"], params["state"], params["error"])
    }

    fun idTokenFrom(tokenResponse: String): String? =
        runCatching { JSONObject(tokenResponse).optString("id_token").ifBlank { null } }.getOrNull()

    private const val PAGE_OK = """<!doctype html><html><head><meta charset="utf-8"><title>JARVIS</title></head>
<body style="background:#050B18;color:#E6F1FF;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
<div style="text-align:center"><h1 style="letter-spacing:.2em;color:#00D4FF">JARVIS</h1><p>You're signed in. You can close this tab and go back to JARVIS.</p></div></body></html>"""

    private const val PAGE_FAIL = """<!doctype html><html><head><meta charset="utf-8"><title>JARVIS</title></head>
<body style="background:#050B18;color:#E6F1FF;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
<div style="text-align:center"><h1 style="letter-spacing:.2em;color:#FF4D4D">JARVIS</h1><p>Sign-in didn't complete. Go back to JARVIS and try again.</p></div></body></html>"""
}
