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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.Speaker

private enum class Section(val label: String) {
    General("General"),
    Voice("Voice"),
    Appearance("Themes"),
}

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
    floatingOrbEnabled: Boolean = true,
    onSetFloatingOrb: (Boolean) -> Unit = {},
    onOpenAssistantSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(Section.General) }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(56.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            // Indented past the menu icon the host draws at the top-left corner.
            // Without this the ☰ lands on top of the "S" and reads as a rendering
            // fault — visible in a device screenshot.
            modifier = Modifier.padding(start = 56.dp, end = 20.dp),
        )

        // The tab row sits directly under the title and NOTHING sits above it but
        // the title.
        //
        // Before this, the three switches were always rendered first and the tabs
        // came after them, so on a real phone the picker was pushed entirely below
        // the fold — the user's words were "it doesn't give me space to scroll
        // through themes at all", with a screenshot showing one clipped row of
        // themes at the very bottom. Anything permanently above a tab row steals
        // the height that every tab then has to share.
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
            Section.entries.forEach { entry ->
                val selected = entry == section
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) JarvisTheme.accent else TextSecondary,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) JarvisTheme.accent.copy(alpha = 0.12f) else JarvisTheme.glass)
                        .border(
                            1.dp,
                            if (selected) JarvisTheme.accent else JarvisTheme.glassBorder,
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { section = entry }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        when (section) {
            // Its own tab, so it owns the screen instead of standing on the others.
            Section.General -> Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                AssistantRow(
                    onOpen = onOpenAssistantSettings,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                FeatureToggle(
                    title = "Wake word — \"Hey Jarvis\"",
                    // Trimmed from three sentences. Each of these was wrapping to
                    // six lines on a phone, which is how three switches managed to
                    // fill an entire screen.
                    blurb = "Summon him by voice from any app. On-device, nothing recorded.",
                    enabled = backgroundWakeEnabled,
                    onToggle = onSetBackgroundWake,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                FeatureToggle(
                    title = "Floating orb",
                    blurb = "Keeps him on screen over other apps. Drag to move, tap to talk.",
                    enabled = floatingOrbEnabled,
                    onToggle = onSetFloatingOrb,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(24.dp))
            }

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
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(14.dp))
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
            color = JarvisTheme.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(JarvisTheme.accent.copy(alpha = 0.12f))
                .border(1.dp, JarvisTheme.accent, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/**
 * A labelled on/off row.
 *
 * Was `WakeToggle`, hard-coded to one feature, until a second toggle needed the
 * identical row. The text is the only thing that ever differed.
 */
@Composable
private fun FeatureToggle(
    title: String,
    blurb: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var on by remember { mutableStateOf(enabled) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(14.dp))
            .clickable { on = !on; onToggle(on) }
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            Text(
                blurb,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            if (on) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = if (on) JarvisTheme.accent else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (on) JarvisTheme.accent.copy(alpha = 0.12f) else JarvisTheme.glass)
                .border(1.dp, if (on) JarvisTheme.accent else JarvisTheme.glassBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}
