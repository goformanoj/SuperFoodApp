package com.jarvis.os.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.HudOrb
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ElectricBlue
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.VoiceUiState
import java.util.Calendar

/**
 * Voice-first home: a centered JARVIS orb that listens as soon as the app
 * opens. No top bar, no Speak button — the orb reacts to your voice and the
 * live transcript shows below it.
 */
@Composable
fun VoiceHome(state: VoiceUiState, modifier: Modifier = Modifier) {
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
        )
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(40.dp))
        HudOrb(amplitude = state.amplitude, size = 300.dp)
        Spacer(Modifier.height(40.dp))

        Text(
            text = state.status,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor(state.orb),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = state.transcript.ifBlank { "Listening for your command…" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.transcript.isBlank()) TextSecondary else TextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun statusColor(orb: OrbState): Color = when (orb) {
    OrbState.Listening -> Cyan
    OrbState.Thinking, OrbState.Speaking -> ElectricBlue
    OrbState.Error -> ErrorRed
    else -> TextSecondary
}

private fun greetingForHour(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}

@Preview(showBackground = true, backgroundColor = 0xFF050B18)
@Composable
private fun VoiceHomePreview() {
    JarvisTheme {
        VoiceHome(
            VoiceUiState(
                orb = OrbState.Listening,
                status = "Listening…",
                transcript = "What's the weather today",
                amplitude = 0.5f,
            ),
        )
    }
}
