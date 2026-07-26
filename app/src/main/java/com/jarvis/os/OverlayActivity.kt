package com.jarvis.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.assistant.AssistantEngine
import com.jarvis.os.service.JarvisService
import com.jarvis.os.ui.components.HudOrb
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ElectricBlue
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.VoiceUiState

/**
 * Translucent, full-screen "listening" panel launched by [JarvisService] when
 * the wake word is heard. Runs the conversation, then closes itself when the
 * conversation ends (or when the user taps away / leaves).
 */
class OverlayActivity : ComponentActivity() {

    private lateinit var engine: AssistantEngine
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = AssistantEngine(applicationContext)
        engine.onConversationEnd = { runOnUiThread { finish() } }
        setContent {
            JarvisTheme {
                val state by engine.state
                OverlayScreen(state = state, onClose = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        JarvisService.pauseWake()
        if (!started) {
            started = true
            val command = intent.getStringExtra(JarvisService.EXTRA_COMMAND)
            engine.wakeUp(command)
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the overlay ends the interaction.
        engine.pause()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
        JarvisService.resumeWake()
    }
}

@Composable
private fun OverlayScreen(state: VoiceUiState, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.9f))
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            HudOrb(orb = state.orb, amplitude = state.amplitude, size = 240.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                text = state.status,
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(state.orb),
            )
            if (state.transcript.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.transcript,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.reply.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.reply,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.orb == OrbState.Error) ErrorRed else Cyan,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun statusColor(orb: OrbState): Color = when (orb) {
    OrbState.Listening -> Cyan
    OrbState.Thinking -> ElectricBlue
    OrbState.Speaking -> SuccessGreen
    OrbState.Error -> ErrorRed
    else -> TextSecondary
}
