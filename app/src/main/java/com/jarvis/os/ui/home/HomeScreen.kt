package com.jarvis.os.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.ui.chat.ChatScreen
import com.jarvis.os.ui.components.HudOrb
import com.jarvis.os.ui.components.ThemeBackdrop
import com.jarvis.os.ui.components.VoiceWave
import com.jarvis.os.ui.debug.DiagnosticsScreen
import com.jarvis.os.ui.calendar.CalendarScreen
import com.jarvis.os.ui.files.FilesScreen
import com.jarvis.os.ui.settings.InstructionsScreen
import com.jarvis.os.ui.settings.SettingsScreen
import com.jarvis.os.ui.speech.VOICE_SAMPLE
import com.jarvis.os.voice.Speaker
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.LocalAccent
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.Surface
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.VoiceUiState
import kotlinx.coroutines.launch
import java.util.Calendar

/** Drawer destinations. Home is the live voice screen; Chat shows history; others are placeholders. */
private enum class Dest(val label: String, val icon: ImageVector, val blurb: String) {
    Home("Home", Icons.Filled.Home, ""),
    Chat("Chat & memory", Icons.Filled.Forum, "Your conversation and what JARVIS remembers."),
    Instructions("Custom instructions", Icons.Filled.EditNote, "What JARVIS always knows about you."),
    Calendar("Calendar", Icons.Filled.CalendarMonth, "Your schedule and events."),
    Files("Files", Icons.Filled.Folder, "Browse and act on your files."),
    Automation("Automation", Icons.Filled.Bolt, "Automate taps, typing, and actions."),
    Settings("Settings", Icons.Filled.Settings, "Voice and appearance."),
    Diagnostics("Diagnostics", Icons.Filled.BugReport, "Self-checks, a typed command box, and the shareable trace."),
}

/** Task row accents, led by the theme so the card is not stuck on cyan. */
@Composable
private fun taskAccents(): List<Color> =
    listOf(LocalAccent.current, LocalPalette.current.highlight, SuccessGreen)

/**
 * App shell: a top-left menu opens the module drawer. Home shows the live voice
 * screen, Chat shows the conversation history, other destinations show themed
 * placeholders. Back returns to Home.
 */
@Composable
fun JarvisApp(
    state: VoiceUiState,
    onClearChat: () -> Unit,
    onWake: () -> Unit = {},
    onSubmitCommand: (String) -> Unit = {},
    voiceOptions: () -> List<Speaker.Option> = { emptyList() },
    currentVoiceId: () -> String? = { null },
    shouldOfferVoiceDownload: () -> Boolean = { false },
    onChooseVoice: (String) -> Unit = {},
    onPreviewVoice: (String) -> Unit = {},
    onVoiceDownloadOffered: () -> Unit = {},
    customInstructions: () -> String = { "" },
    learnedFacts: () -> List<String> = { emptyList() },
    onSaveInstructions: (String) -> Unit = {},
    onForgetFact: (String) -> Unit = {},
    backgroundWakeEnabled: () -> Boolean = { true },
    onSetBackgroundWake: (Boolean) -> Unit = {},
    palette: JarvisPalette = JarvisPalette.Default,
    onSelectPalette: (JarvisPalette) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(Dest.Home) }

    if (current != Dest.Home) {
        BackHandler { current = Dest.Home }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            JarvisDrawer(
                selected = current,
                onSelect = {
                    current = it
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Box(modifier = modifier.fillMaxSize().background(palette.background)) {
            when (current) {
                Dest.Home -> HomeContent(state, onWake)
                Dest.Chat -> ChatScreen(state.messages, onClearChat)
                Dest.Diagnostics -> DiagnosticsScreen(onSubmitCommand = onSubmitCommand)
                Dest.Settings -> SettingsScreen(
                    voices = voiceOptions(),
                    currentVoiceId = currentVoiceId(),
                    shouldOfferVoiceDownload = shouldOfferVoiceDownload(),
                    onChooseVoice = onChooseVoice,
                    onPreviewVoice = { onPreviewVoice(VOICE_SAMPLE) },
                    onVoiceDownloadOffered = onVoiceDownloadOffered,
                    palette = palette,
                    onSelectPalette = onSelectPalette,
                    backgroundWakeEnabled = backgroundWakeEnabled(),
                    onSetBackgroundWake = onSetBackgroundWake,
                )
                Dest.Instructions -> InstructionsScreen(
                    initial = customInstructions(),
                    learned = learnedFacts(),
                    onSave = onSaveInstructions,
                    onForget = onForgetFact,
                )
                Dest.Calendar -> CalendarScreen()
                Dest.Files -> FilesScreen()
                else -> PlaceholderScreen(current)
            }

            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Open menu",
                tint = TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .systemBarsPadding()
                    .padding(12.dp)
                    .clip(CircleShape)
                    .clickable { scope.launch { drawerState.open() } }
                    .padding(6.dp)
                    .size(26.dp),
            )
        }
    }
}

@Composable
private fun HomeContent(state: VoiceUiState, onWake: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        // The backdrop sits behind the scrolling content, so the starfield stays
        // put while the page moves over it — the orb lives in a space rather than
        // floating on flat black.
        ThemeBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HeroSection(state = state, height = viewport, onWake = onWake)
            ScheduleSection()
        }
    }
}

@Composable
private fun HeroSection(state: VoiceUiState, height: Dp, onWake: () -> Unit = {}) {
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    // Show live transcript/reply only during an active conversation; hide when asleep.
    val active = state.orb != OrbState.Idle && state.orb != OrbState.Offline

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(greeting, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(28.dp))
        // Tapping the orb wakes JARVIS without the wake word — enabled only while
        // it is asleep (idle), so a live conversation isn't interrupted by a tap.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = !active) { onWake() },
            contentAlignment = Alignment.Center,
        ) {
            HudOrb(orb = state.orb, amplitude = state.amplitude, size = 280.dp)
        }
        Spacer(Modifier.height(8.dp))

        // The waveform strip from the designs. Driven by the real mic level, so
        // it shows whether JARVIS can currently hear you rather than just moving.
        VoiceWave(amplitude = state.amplitude)
        Spacer(Modifier.height(12.dp))

        // Uppercase and widely tracked, as the status block reads in the designs.
        // The text is the app's REAL state, not the decorative three lines from
        // the artwork — a status that always said "COMMAND ACCEPTED" would be a lie.
        Text(
            text = state.status.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
            color = statusColor(state.orb),
            textAlign = TextAlign.Center,
        )

        if (active) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.transcript.ifBlank { "Listening…" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.transcript.isBlank()) TextSecondary else TextPrimary,
                textAlign = TextAlign.Center,
            )
            if (state.reply.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.reply,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.orb == OrbState.Error) ErrorRed else LocalAccent.current,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ScheduleSection() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            text = "Today's Tasks",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(12.dp))
        TasksCard()
    }
}

@Composable
private fun PlaceholderScreen(dest: Dest) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(dest.icon, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(dest.label, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text("COMING SOON", style = MaterialTheme.typography.labelSmall, color = LocalAccent.current)
        Spacer(Modifier.height(16.dp))
        Text(
            text = dest.blurb,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun JarvisDrawer(selected: Dest, onSelect: (Dest) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = Surface) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "J.A.R.V.I.S.",
            style = MaterialTheme.typography.headlineSmall,
            color = LocalAccent.current,
            modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
        )
        Text(
            text = "OPERATING SYSTEM",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = 24.dp, bottom = 16.dp),
        )
        HorizontalDivider(color = GlassBorder)
        Spacer(Modifier.height(8.dp))

        Dest.entries.forEach { dest ->
            NavigationDrawerItem(
                label = { Text(dest.label, style = MaterialTheme.typography.titleMedium) },
                selected = dest == selected,
                onClick = { onSelect(dest) },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = SurfaceGlass,
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = LocalAccent.current,
                    unselectedIconColor = LocalAccent.current,
                    selectedTextColor = TextPrimary,
                    unselectedTextColor = TextPrimary,
                ),
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

/**
 * The real device calendar, not an invented list.
 *
 * This card used to show three hardcoded fake events, which made the whole
 * screen decorative — it said "Team sync 10:00" on a phone with an empty
 * calendar. It now reads the same source the assistant answers from, so the
 * screen and JARVIS can never disagree.
 */
@Composable
private fun TasksCard() {
    val context = LocalContext.current
    // Re-read on every entry to Home: events change outside the app, and JARVIS
    // itself adds and deletes them.
    val agenda = remember { CalendarReader.agenda(context) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceGlass)
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        when {
            // Null and empty mean different things, and the old fake list could
            // express neither.
            agenda == null -> EmptyNote("Grant calendar access and your real schedule appears here.")
            agenda.isEmpty() -> EmptyNote("Nothing scheduled in the next couple of days.")
            else -> Column {
                val accents = taskAccents()
                agenda.take(MAX_AGENDA_ROWS).forEachIndexed { index, event ->
                    EventRow(event, accents[index % accents.size])
                    if (index != agenda.take(MAX_AGENDA_ROWS).lastIndex) {
                        Spacer(Modifier.height(14.dp))
                    }
                }
                if (agenda.size > MAX_AGENDA_ROWS) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "+${agenda.size - MAX_AGENDA_ROWS} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
}

@Composable
private fun EventRow(event: CalendarReader.Event, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            event.title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Text(event.timeLabel(), style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

private const val MAX_AGENDA_ROWS = 4

@Composable
private fun statusColor(orb: OrbState): Color = when (orb) {
    OrbState.Listening -> LocalAccent.current
    OrbState.Thinking -> LocalPalette.current.secondary
    OrbState.Speaking -> SuccessGreen
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
private fun JarvisAppPreview() {
    JarvisTheme {
        JarvisApp(
            state = VoiceUiState(
                orb = OrbState.Speaking,
                status = "Speaking…",
                transcript = "What's my schedule today",
                reply = "You have a team sync at 10:00 and a call with your mentor at 18:30.",
                amplitude = 0.4f,
            ),
            onClearChat = {},
        )
    }
}
