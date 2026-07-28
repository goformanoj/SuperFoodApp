package com.jarvis.os.ui.speech

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.ui.theme.WarningOrange
import com.jarvis.os.voice.Speaker

private const val SAMPLE = "Good evening. All systems are online and ready when you are."

/**
 * Choose how JARVIS sounds, inside JARVIS.
 *
 * Ranking the installed voices automatically only helps if good ones happen to
 * be installed. Nobody installing this app is going to go hunting through
 * Android's accessibility settings to download better speech data, so the
 * choice — and the download — are offered here instead.
 */
@Composable
fun SpeechScreen(
    voices: List<Speaker.Option>,
    currentVoiceId: String?,
    shouldOfferDownload: Boolean,
    onSelect: (String) -> Unit,
    onPreview: () -> Unit,
    onDownloadOffered: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = true,
) {
    val context = LocalContext.current
    var selected by remember(currentVoiceId) { mutableStateOf(currentVoiceId) }
    var refresh by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .then(if (embedded) Modifier else Modifier.systemBarsPadding())
            .padding(horizontal = 20.dp),
    ) {
        if (!embedded) Spacer(Modifier.height(56.dp))
        Text("Voice", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        Text(
            "How JARVIS sounds when it speaks to you.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (shouldOfferDownload) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, WarningOrange.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Text("Better voices are available", style = MaterialTheme.typography.titleSmall, color = WarningOrange)
                Text(
                    "This phone only has basic speech data installed. Downloading the " +
                        "high-quality English voices takes a moment and makes JARVIS sound far " +
                        "more natural.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
                Action("Download voices") {
                    onDownloadOffered()
                    // The system handles the download; we only open the right place.
                    runCatching {
                        context.startActivity(
                            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Available voices",
                style = MaterialTheme.typography.labelLarge,
                color = Cyan,
                modifier = Modifier.weight(1f),
            )
            Action("Test") { onPreview() }
        }
        Spacer(Modifier.height(10.dp))

        if (voices.isEmpty()) {
            Text(
                "No speech voices found yet. If this persists, the device may be missing a " +
                    "text-to-speech engine.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            voices.forEach { option ->
                VoiceRow(
                    option = option,
                    selected = option.id == selected,
                    onClick = {
                        selected = option.id
                        onSelect(option.id)
                        refresh++
                        onPreview()
                    },
                )
            }
            Text(
                "Tap a voice to hear it. Your choice is remembered.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VoiceRow(option: Speaker.Option, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Cyan.copy(alpha = 0.12f) else SurfaceGlass)
            .border(
                1.dp,
                if (selected) Cyan else GlassBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Cyan else TextPrimary,
            )
            if (option.needsNetwork) {
                Text(
                    "needs internet",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        if (selected) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Cyan),
            )
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Background),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** The line JARVIS reads when you audition a voice. */
const val VOICE_SAMPLE = SAMPLE
