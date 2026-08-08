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
            JarvisTheme(palette) {
                Surface(modifier = Modifier.fillMaxSize(), color = palette.background) {
                    val state by engine.state
                    JarvisApp(
                        state = state,
                        onClearChat = { engine.clearConversation() },
                        onWake = { engine.wake() },
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
                        palette = palette,
                        onSelectPalette = {
                            palette = it
                            engine.saveThemeId(it.id)
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
        // Woken by "Hey Jarvis" from the background: acknowledge and listen for the
        // command. Consume the flag so re-opening the app later doesn't re-trigger.
        if (intent?.getBooleanExtra(EXTRA_WOKE_BY_HOTWORD, false) == true) {
            intent.removeExtra(EXTRA_WOKE_BY_HOTWORD)
            engine.wakeFromHotword()
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
