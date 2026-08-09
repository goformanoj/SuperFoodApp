package com.jarvis.os.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.speech.SpeechScreen
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.Speaker

private enum class Section(val label: String) { Voice("Voice"), Appearance("Appearance") }

/**
 * One home for how JARVIS sounds and looks, rather than a tab each. Both are
 * "settings" in the way anyone would expect, and the drawer is shorter for it.
 */
@Composable
fun SettingsScreen(
    voices: List<Speaker.Option>,
    currentVoiceId: String?,
    shouldOfferVoiceDownload: Boolean,
    onChooseVoice: (String) -> Unit,
    onPreviewVoice: () -> Unit,
    onVoiceDownloadOffered: () -> Unit,
    palette: JarvisPalette,
    onSelectPalette: (JarvisPalette) -> Unit,
    backgroundWakeEnabled: Boolean = true,
    onSetBackgroundWake: (Boolean) -> Unit = {},
    onOpenAssistantSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(Section.Voice) }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(56.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        AssistantRow(
            onOpen = onOpenAssistantSettings,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        )

        WakeToggle(
            enabled = backgroundWakeEnabled,
            onToggle = onSetBackgroundWake,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Section.entries.forEach { entry ->
                val selected = entry == section
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Cyan else TextSecondary,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Cyan.copy(alpha = 0.12f) else SurfaceGlass)
                        .border(
                            1.dp,
                            if (selected) Cyan else GlassBorder,
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { section = entry }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        when (section) {
            Section.Voice -> SpeechScreen(
                voices = voices,
                currentVoiceId = currentVoiceId,
                shouldOfferDownload = shouldOfferVoiceDownload,
                onSelect = onChooseVoice,
                onPreview = onPreviewVoice,
                onDownloadOffered = onVoiceDownloadOffered,
                modifier = Modifier.fillMaxWidth(),
            )
            Section.Appearance -> ThemesScreen(
                current = palette,
                onSelect = onSelectPalette,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Guides the user to set JARVIS as the assist app, for mic-free gesture launch. */
@Composable
private fun AssistantRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceGlass)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                "Open JARVIS with a gesture",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                "Set JARVIS as your assistant app, then long-press power (or swipe from a " +
                    "corner) to open it instantly — mic ready, no wake word, no battery cost.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "Set up",
            style = MaterialTheme.typography.labelLarge,
            color = Cyan,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Cyan.copy(alpha = 0.12f))
                .border(1.dp, Cyan, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/** A labelled on/off row for the background "Hey Jarvis" wake word. */
@Composable
private fun WakeToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var on by remember { mutableStateOf(enabled) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceGlass)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .clickable { on = !on; onToggle(on) }
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                "Wake word — \"Hey Jarvis\"",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                "Summon JARVIS by voice from any app. On-device, nothing recorded. " +
                    "Uses some battery and shows an ongoing notification.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            if (on) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = if (on) Cyan else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (on) Cyan.copy(alpha = 0.12f) else SurfaceGlass)
                .border(1.dp, if (on) Cyan else GlassBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}
