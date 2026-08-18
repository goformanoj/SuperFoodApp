package com.jarvis.os

import android.app.Activity

/**
 * Generated at build time from the Gradle `buildConfigField`s, so it does not
 * exist as a source file to compile. The keys are injected from GitHub Actions
 * secrets and are blank everywhere else — including here, which is correct:
 * nothing in this harness may make a network call.
 */
object BuildConfig {
    const val GROQ_API_KEY: String = ""
    const val GEMINI_API_KEY: String = ""
    const val DEBUG: Boolean = true
}

/**
 * The real one is excluded from this harness because it is the Compose entry
 * point. [com.jarvis.os.control.HotwordService] only ever names the class and
 * one extra key, so this is enough.
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_WOKE_BY_HOTWORD = "woke_by_hotword"
    }
}
