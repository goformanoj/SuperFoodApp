package com.jarvis.os.data

import android.content.Context
import java.io.File

/**
 * Moves the app's pre-profile data into the active account's partition, exactly once.
 *
 * Before per-account partitioning, chats, memory, instructions, files and usage all
 * lived in single global stores. When this ships, those globals would look empty
 * because the code now reads a scoped name — so on first run we lift the existing
 * data into the *current* profile (whoever is signed in, or guest) and clear the
 * globals, so nothing is lost and a later guest starts genuinely empty.
 *
 * Guarded by a flag so it runs a single time regardless of who is signed in then.
 */
object ProfileMigration {

    private const val META = "jarvis_meta"
    private const val FLAG = "profiled_v1"

    fun runOnce(context: Context, profileId: String) {
        val app = context.applicationContext
        val meta = app.getSharedPreferences(META, Context.MODE_PRIVATE)
        if (meta.getBoolean(FLAG, false)) return

        migrateChat(app, profileId)
        migrateProfileData(app, profileId)
        migrateUsage(app, profileId)
        migrateArtifacts(app, profileId)

        meta.edit().putBoolean(FLAG, true).apply()
    }

    private fun migrateChat(app: Context, profileId: String) {
        val legacy = app.getSharedPreferences("jarvis_chat", Context.MODE_PRIVATE)
        val history = legacy.getString("history", null) ?: return
        app.getSharedPreferences(Profiles.scoped("jarvis_chat", profileId), Context.MODE_PRIVATE)
            .edit().putString("history", history).apply()
        legacy.edit().remove("history").apply()
    }

    /** Custom instructions + learned facts used to sit in the global jarvis_user file. */
    private fun migrateProfileData(app: Context, profileId: String) {
        val legacy = app.getSharedPreferences("jarvis_user", Context.MODE_PRIVATE)
        val instructions = legacy.getString("custom_instructions", null)
        val facts = legacy.getString("learned_facts", null)
        if (instructions == null && facts == null) return
        val dst = app.getSharedPreferences(Profiles.scoped("jarvis_profile", profileId), Context.MODE_PRIVATE).edit()
        if (instructions != null) dst.putString("custom_instructions", instructions)
        if (facts != null) dst.putString("learned_facts", facts)
        dst.apply()
        // Leave the device/appearance keys (theme, backdrop, wake, orb) in place —
        // only the per-account content moves.
        legacy.edit().remove("custom_instructions").remove("learned_facts").apply()
    }

    private fun migrateUsage(app: Context, profileId: String) {
        val legacy = app.getSharedPreferences("jarvis_usage", Context.MODE_PRIVATE)
        val day = legacy.getString("day", null) ?: return
        app.getSharedPreferences(Profiles.scoped("jarvis_usage", profileId), Context.MODE_PRIVATE)
            .edit()
            .putString("day", day)
            .putInt("used", legacy.getInt("used", 0))
            .putInt("cap", legacy.getInt("cap", 60_000))
            .apply()
        legacy.edit().clear().apply()
    }

    private fun migrateArtifacts(app: Context, profileId: String) {
        val legacy = File(app.filesDir, "artifacts")
        val scoped = File(app.filesDir, Profiles.scoped("artifacts", profileId))
        if (legacy.exists() && !scoped.exists()) {
            // A rename moves the index and every file in one move; if it fails the
            // files are simply left where they are (no data lost, just unlisted).
            runCatching { legacy.renameTo(scoped) }
        }
    }
}
