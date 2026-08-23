package com.jarvis.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.jarvis.os.assistant.AssistantEngine
import com.jarvis.os.ui.home.JarvisApp
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.JarvisTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
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
