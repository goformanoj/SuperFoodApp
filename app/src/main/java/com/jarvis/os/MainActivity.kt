package com.jarvis.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.jarvis.os.assistant.AssistantEngine
import com.jarvis.os.ui.home.JarvisApp
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.JarvisTheme

/** How long the splash is held before it may hand over. */
private const val SPLASH_MIN_MS = 650L

/** How long it takes to leave. */
private const val SPLASH_FADE_MS = 320L

class MainActivity : ComponentActivity() {

    private lateinit var engine: AssistantEngine
    private var permissionsAsked = false

    // One launcher for all permissions — requesting mic and calendar in a single
    // sequence avoids the second request being dropped while the first dialog shows.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
            engine.onMicPermission(micOk)
        }

    /**
     * When the splash may hand over. See [installSplash].
     */
    private var splashUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // BEFORE super.onCreate, which is not a style preference: the library
        // installs the splash by swapping the activity's theme, and the theme has
        // to be in place before the window is created.
        installSplash()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = AssistantEngine(applicationContext)
        setContent {
            // Held above the theme so a change repaints the whole app immediately,
            // background and surfaces included — not only the accent.
            var palette by remember { mutableStateOf(JarvisPalette.fromId(engine.themeId())) }
            // Held here for the same reason the palette is: a background change
            // has to repaint every destination at once, and the backdrop is drawn
            // by the host behind all of them.
            var backdropId by remember { mutableStateOf(engine.backdropId()) }
            JarvisTheme(palette) {
                Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
                    val state by engine.state
                    JarvisApp(
                        state = state,
                        // A LAMBDA, deliberately. Reading `engine.amplitude.value`
                        // here would make this composable depend on it, and this
                        // is the composable the whole app hangs off — which is
                        // precisely the bug being fixed. Read inside the Canvas
                        // that draws it, and a mic level costs one invalidation.
                        amplitude = { engine.amplitude.value },
                        onClearChat = { engine.clearConversation() },
                        onWake = { engine.wake() },
                        onInterrupt = { engine.interrupt() },
                        onSubmitCommand = { engine.submitText(it) },
                        voiceOptions = { engine.voiceOptions() },
                        currentVoiceId = { engine.currentVoiceId() },
                        shouldOfferVoiceDownload = { engine.shouldOfferVoiceDownload() },
                        onChooseVoice = { engine.chooseVoice(it) },
                        onPreviewVoice = { engine.previewVoice(it) },
                        onVoiceDownloadOffered = { engine.markVoiceDownloadOffered() },
                        customInstructions = { engine.customInstructions() },
                        learnedFacts = { engine.learnedFacts() },
                        onSaveInstructions = { engine.saveCustomInstructions(it) },
                        onForgetFact = { engine.forgetFact(it) },
                        backgroundWakeEnabled = { engine.backgroundWakeEnabled() },
                        onSetBackgroundWake = { engine.setBackgroundWake(it) },
                        floatingOrbEnabled = { engine.floatingOrbEnabled() },
                        onSetFloatingOrb = { engine.setFloatingOrb(it) },
                        onOpenAssistantSettings = { engine.openAssistantSettings() },
                        palette = palette,
                        backdropId = backdropId,
                        onSelectBackdrop = {
                            backdropId = it
                            engine.saveBackdropId(it)
                        },
                        onSelectPalette = {
                            palette = it
                            engine.saveThemeId(it.id)
                            // Choosing a theme hands back its own background —
                            // "when u select a theme u get the default background
                            // which comes with it". Clearing the id rather than
                            // storing the new theme's backdrop keeps the setting
                            // meaning "follow the theme", so the next theme change
                            // follows too.
                            backdropId = ""
                            engine.saveBackdropId("")
                        },
                    )
                }
            }
        }
    }

    // The background hotword service launches us with this flag set. singleTask
    // (manifest) means an existing instance is reused and gets the new intent here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        engine.resume()
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) engine.onMicPermission(true)
        requestMissingPermissions()
        // Summoned — either by the assist gesture (default digital-assistant app) or
        // by the background "Hey Jarvis". Acknowledge and listen for the command,
        // then neutralise the intent so a later ordinary reopen doesn't re-trigger.
        val i = intent
        when {
            i?.action == Intent.ACTION_ASSIST -> {
                setIntent(Intent(this, MainActivity::class.java))
                engine.summon("assist gesture")
            }
            i?.getBooleanExtra(EXTRA_WOKE_BY_HOTWORD, false) == true -> {
                i.removeExtra(EXTRA_WOKE_BY_HOTWORD)
                engine.summon("Hey Jarvis")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED.filter { !hasPermission(it) }
        if (missing.isNotEmpty() && !permissionsAsked) {
            permissionsAsked = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * The splash: how long it stays, and how it leaves.
     *
     * **On the duration.** Google's guidance is not to pad a splash artificially,
     * and that guidance is right — a splash exists to cover work, and time spent
     * looking at a logo is time taken from the user. But a splash that vanishes in
     * 90ms on a fast phone does not read as a splash at all; it reads as a flicker,
     * which is worse than none. So this holds for [SPLASH_MIN_MS] **or** until the
     * app is ready, whichever comes later. On a slow cold start it costs nothing,
     * because the app was not ready anyway. On a fast one it costs a little over
     * half a second, which is the price of the launch looking deliberate.
     *
     * `setKeepOnScreenCondition` is polled every frame, so this must be cheap —
     * a clock comparison and nothing else.
     *
     * **On the exit.** The default is a hard cut from splash to app. Fading the
     * splash out while lifting it slightly is what makes the two feel like one
     * movement rather than two screens; the icon leaving toward the viewer matches
     * the direction the app arrives from.
     */
    private fun installSplash() {
        val splash = installSplashScreen()
        splashUntil = SystemClock.uptimeMillis() + SPLASH_MIN_MS
        splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() < splashUntil }
        splash.setOnExitAnimationListener { provider ->
            val view = provider.view
            view.animate()
                .alpha(0f)
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(SPLASH_FADE_MS)
                .setInterpolator(AccelerateInterpolator())
                // `remove()` is mandatory, not tidy-up: the splash view stays on
                // top of the window until it is called, so forgetting it leaves a
                // frozen image over a live app.
                .withEndAction { provider.remove() }
                .start()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Intent extra set by [com.jarvis.os.control.HotwordService] on detection. */
        const val EXTRA_WOKE_BY_HOTWORD = "woke_by_hotword"

        private val REQUIRED: Array<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            // Android 13+ needs this for the work-session notification to be seen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
}
