package com.jarvis.os.ui.settings

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.components.SettingSwitchRow
import com.jarvis.os.ui.components.SettingActionRow
import com.jarvis.os.ui.speech.SpeechScreen
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.JarvisTheme
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
    backdropId: String = "",
    onSelectBackdrop: (String) -> Unit = {},
    backgroundWakeEnabled: Boolean = true,
    onSetBackgroundWake: (Boolean) -> Unit = {},
    floatingOrbEnabled: Boolean = true,
    onSetFloatingOrb: (Boolean) -> Unit = {},
    onOpenAssistantSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(Section.General) }
    // The switches own their visual state so the thumb moves the instant it is
    // touched, rather than after the preference has been written and read back.
    var wakeWord by remember { mutableStateOf(backgroundWakeEnabled) }
    var floatingOrb by remember { mutableStateOf(floatingOrbEnabled) }

    Column(modifier.fillMaxSize()) {
        // The shared header. It carries the indent past the ☰ the host draws in
        // the corner — a fact that was written out separately on three screens,
        // with three different numbers, until it lived in one place.
        ScreenHeader(title = "Settings", modifier = Modifier.padding(horizontal = 20.dp))

        // The tab row sits directly under the title and NOTHING sits above it but
        // the title.
        //
        // Before this, the three switches were always rendered first and the tabs
        // came after them, so on a real phone the picker was pushed entirely below
        // the fold — the user's words were "it doesn't give me space to scroll
        // through themes at all", with a screenshot showing one clipped row of
        // themes at the very bottom. Anything permanently above a tab row steals
        // the height that every tab then has to share.
        //
        // The pills take an EQUAL THIRD each and their labels never wrap.
        // Sized to their own content instead, the row ran out of width and the
        // last pill wrapped mid-word — "Theme" over "s" — because a Row hands
        // out space in order and the last child gets whatever is left. Equal
        // thirds also means adding a fourth section later shrinks all four
        // evenly rather than breaking only the one on the end.
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section.entries.forEach { entry ->
                val selected = entry == section
                Text(
                    entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) JarvisTheme.accent else TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) JarvisTheme.accent.copy(alpha = 0.12f) else JarvisTheme.glass)
                        .border(
                            1.dp,
                            if (selected) JarvisTheme.accent else JarvisTheme.glassBorder,
                            RoundedCornerShape(20.dp),
                        )
                        .clickable { section = entry }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
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
                SettingActionRow(
                    title = "Open with a gesture",
                    description = "Set JARVIS as your assistant app, then long-press power " +
                        "to open it instantly — mic ready, no wake word, no battery cost.",
                    actionLabel = "Set up",
                    onAction = onOpenAssistantSettings,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                SettingSwitchRow(
                    title = "Wake word",
                    description = "Summon him by saying \"Hey Jarvis\" from any app. " +
                        "On-device, nothing recorded.",
                    checked = wakeWord,
                    onCheckedChange = { wakeWord = it; onSetBackgroundWake(it) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                SettingSwitchRow(
                    title = "Floating orb",
                    description = "Keeps him on screen over other apps. Drag to move, tap to talk.",
                    checked = floatingOrb,
                    onCheckedChange = { floatingOrb = it; onSetFloatingOrb(it) },
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
                backdropId = backdropId,
                onSelectBackdrop = onSelectBackdrop,
            )
        }
    }
}

// Both rows on this screen come from `Controls.kt` now.
//
// They used to be written here: an outlined pill reading "On" or "Off" beside the
// title, and a second pill reading "Set up". A pill that says "Off" looks like a
// button, invites a tap, and does not say what tapping will do — and it is not
// the control Android users already know. A boolean gets a switch on every
// settings screen on the platform, and matching that is most of the difference
// between an app that feels native and one that feels drawn.
//
// The action row also moved its button BELOW the text. Beside a two-line title it
// squeezed the title into a column barely wider than the button itself, which is
// what broke "Open JARVIS with a gesture" across three lines on a real phone.
