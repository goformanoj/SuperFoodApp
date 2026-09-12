package com.jarvis.os.ai

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.jarvis.os.BuildConfig
import com.jarvis.os.debug.DebugLog

/**
 * Google sign-in on device, WITHOUT the Firebase SDK (Part E).
 *
 * Credential Manager returns a Google ID token, which [Identity.linkGoogle] then
 * exchanges for a Firebase token over the Auth REST API — so, like the rest of
 * identity here, this needs no `google-services` plugin or committed json, only a
 * public Web client id ([BuildConfig.GOOGLE_WEB_CLIENT_ID]) and the app's SHA-1
 * registered in Firebase. Purely device glue: the tested logic (the REST exchange,
 * the response parsing, the link-vs-sign-in decision) lives in [Identity]. Excluded
 * from the off-device gate because androidx.credentials resolves only in CI.
 */
object GoogleAuth {

    /** True when Google sign-in can run — a Web client id and a Firebase key are set. */
    fun isConfigured(): Boolean =
        BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() && Identity.isConfigured()

    /**
     * Show the Google account chooser, then upgrade the current identity to it.
     * Throws [IdentityException] with a short, safe reason on cancel or failure.
     */
    suspend fun signIn(context: Context): Identity.Account {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            throw IdentityException("Google sign-in isn't configured yet")
        }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            // Offer any Google account on the device, not only ones already used here,
            // so a first-time sign-in is not an empty sheet.
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val googleIdToken = try {
            val result = CredentialManager.create(context).getCredential(context, request)
            val cred = result.credential
            if (cred is CustomCredential &&
                cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleIdTokenCredential.createFrom(cred.data).idToken
            } else {
                throw IdentityException("Unexpected sign-in response")
            }
        } catch (e: GetCredentialException) {
            // Cancelled, no Google account, or Play services unavailable — all safe to
            // surface without detail (never leak the token/credential internals).
            throw IdentityException("Google sign-in was cancelled or unavailable")
        }

        DebugLog.log(DebugLog.Stage.SESSION, "google sign-in: credential received, linking identity")
        return Identity.linkGoogle(googleIdToken)
    }
}
