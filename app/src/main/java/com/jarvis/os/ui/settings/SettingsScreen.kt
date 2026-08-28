package com.jarvis.os.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
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
import com.jarvis.os.ui.components.SectionLabel
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
import com.jarvis.os.voice.LanguagePrefs
import com.jarvis.os.voice.Speaker

/**
 * Where a settings page can take you.
 *
 * A DRILL-DOWN, replacing three tab pills across the top.
 *
 * Tabs were the wrong shape for this and cost real things. They force every
 * section to share one screen's height, so the theme picker got a third of the
 * page and the user's words were *"it doesn't give me space to scroll through
 * themes at all"*. They also mean an embedded screen draws its own title INSIDE a
 * page that already has one — which is how three headers on `ThemesScreen` ended
 * up printed on top of their own descriptions.
 *
 * Every settings app on the platform is an index of rows that open a page. It is
 * what people already know, each page gets the whole screen, and a section can be
 * added without stealing width from the others.
 */
private enum class Detail(val title: String) {
    Voice("Voice"),
    Languages("Languages"),
    Appearance("Appearance"),
}

/**
 * Settings: an index, and pages it opens.
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
    languagePrefs: LanguagePrefs = LanguagePrefs.DEFAULT,
    onSetLanguages: (LanguagePrefs) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<Detail?>(null) }
    // The switches own their visual state so the thumb moves the instant it is
    // touched, rather than after the preference has been written and read back.
    var wakeWord by remember { mutableStateOf(backgroundWakeEnabled) }
    var floatingOrb by remember { mutableStateOf(floatingOrbEnabled) }

    // The system back gesture closes a page before it leaves Settings, which is
    // what a drill-down has to do — otherwise opening Voice and pressing back
    // throws the user out of the whole screen.
    BackHandler(enabled = open != null) { open = null }

    val page = open
    if (page != null) {
        Column(modifier.fillMaxSize()) {
            DetailHeader(title = page.title, onBack = { open = null })
            when (page) {
                Detail.Voice -> SpeechScreen(
                    voices = voices,
                    currentVoiceId = currentVoiceId,
                    shouldOfferDownload = shouldOfferVoiceDownload,
                    onSelect = onChooseVoice,
                    onPreview = onPreviewVoice,
                    onDownloadOffered = onVoiceDownloadOffered,
                    modifier = Modifier.fillMaxWidth(),
                )
                Detail.Languages -> LanguagesScreen(
                    current = languagePrefs,
                    onSelect = onSetLanguages,
                    modifier = Modifier.fillMaxWidth(),
                )
                Detail.Appearance -> ThemesScreen(
                    current = palette,
                    onSelect = onSelectPalette,
                    modifier = Modifier.fillMaxWidth(),
                    backdropId = backdropId,
                    onSelectBackdrop = onSelectBackdrop,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(title = "Settings")

        SectionLabel("How JARVIS sounds and looks")
        NavRow(
            icon = Icons.Filled.RecordVoiceOver,
            title = "Voice",
            value = voices.firstOrNull { it.id == currentVoiceId }?.label ?: "System default",
            onClick = { open = Detail.Voice },
        )
        Spacer(Modifier.height(10.dp))
        NavRow(
            icon = Icons.Filled.Palette,
            title = "Appearance",
            // The theme's own name as the row's value, so the index says what is
            // set without being opened. A settings row that shows only its label
            // makes the user go in to find out what it is.
            value = palette.displayName,
            onClick = { open = Detail.Appearance },
        )

        SectionLabel("How JARVIS listens")
        NavRow(
            icon = Icons.Filled.Language,
            title = "Languages",
            // The chosen pair on the row, so the index says what is set. Up to two.
            value = languagePrefs.understood.joinToString("  ·  ") { it.label },
            onClick = { open = Detail.Languages },
        )
        Spacer(Modifier.height(10.dp))
        SettingSwitchRow(
            title = "Wake word",
            description = "Summon him by saying \"Hey Jarvis\" from any app. On-device, nothing recorded.",
            checked = wakeWord,
            onCheckedChange = { wakeWord = it; onSetBackgroundWake(it) },
        )
        Spacer(Modifier.height(10.dp))
        SettingSwitchRow(
            title = "Floating orb",
            description = "Keeps him on screen over other apps. Drag to move, tap to talk.",
            checked = floatingOrb,
            onCheckedChange = { floatingOrb = it; onSetFloatingOrb(it) },
        )
        Spacer(Modifier.height(10.dp))
        SettingActionRow(
            title = "Open with a gesture",
            description = "Set JARVIS as your assistant app, then long-press power to open " +
                "him instantly — mic ready, no wake word, no battery cost.",
            actionLabel = "Set up",
            onAction = onOpenAssistantSettings,
        )

        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The header on an opened page: a back arrow and the page's name.
 *
 * A drill-down needs a visible way back as well as the gesture. The gesture is
 * invisible, and a page you can only leave by knowing a gesture is a page people
 * get stuck on.
 */
@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 20.dp, top = 52.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to settings",
            tint = TextPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onBack() }
                .padding(8.dp)
                .size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
    }
}

/**
 * A row that opens a page: icon, name, the value currently set, and a chevron.
 *
 * The chevron is the part that matters. Without it a row that navigates and a row
 * that toggles look identical, and the only way to find out which is which is to
 * tap and see.
 */
@Composable
private fun NavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val accent = JarvisTheme.accent
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.card)
            .border(1.dp, JarvisTheme.cardBorder, shape)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp),
        )
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
