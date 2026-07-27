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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.os.data.TaskItem
import com.jarvis.os.data.todaysTasks
import com.jarvis.os.ui.chat.ChatScreen
import com.jarvis.os.ui.components.HudOrb
import com.jarvis.os.ui.debug.DiagnosticsScreen
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ElectricBlue
import com.jarvis.os.ui.theme.ErrorRed
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.Surface
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.ui.theme.WarningOrange
import com.jarvis.os.voice.OrbState
import com.jarvis.os.voice.VoiceUiState
import kotlinx.coroutines.launch
import java.util.Calendar

/** Drawer destinations. Home is the live voice screen; Chat shows history; others are placeholders. */
private enum class Dest(val label: String, val icon: ImageVector, val blurb: String) {
    Home("Home", Icons.Filled.Home, ""),
    Speech("Speech", Icons.Filled.Mic, "Voice and speech options."),
    Chat("Chat", Icons.Filled.Forum, "A terminal-style history of your conversation."),
    Memory("Memory", Icons.Filled.Memory, "Reminders, projects, and what JARVIS remembers."),
    Files("Files", Icons.Filled.Folder, "Browse and act on your files."),
    Calendar("Calendar", Icons.Filled.CalendarMonth, "Your schedule and events."),
    Vision("Vision", Icons.Filled.Visibility, "Let JARVIS see and understand your screen."),
    Automation("Automation", Icons.Filled.Bolt, "Automate taps, typing, and actions."),
    Skills("Skills", Icons.Filled.Extension, "Plugins and extra abilities."),
    Settings("Settings", Icons.Filled.Settings, "Preferences and configuration."),
    Diagnostics("Diagnostics", Icons.Filled.BugReport, "Self-checks, a typed command box, and the shareable trace."),
}

private val taskAccents = listOf(Cyan, WarningOrange, SuccessGreen)

/**
 * App shell: a top-left menu opens the module drawer. Home shows the live voice
 * screen, Chat shows the conversation history, other destinations show themed
 * placeholders. Back returns to Home.
 */
@Composable
fun JarvisApp(
    state: VoiceUiState,
    onClearChat: () -> Unit,
    onSubmitCommand: (String) -> Unit = {},
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
        Box(modifier = modifier.fillMaxSize().background(Background)) {
            when (current) {
                Dest.Home -> HomeContent(state)
                Dest.Chat -> ChatScreen(state.messages, onClearChat)
                Dest.Diagnostics -> DiagnosticsScreen(onSubmitCommand = onSubmitCommand)
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
private fun HomeContent(state: VoiceUiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HeroSection(state = state, height = viewport)
            ScheduleSection()
        }
    }
}

@Composable
private fun HeroSection(state: VoiceUiState, height: Dp) {
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
        HudOrb(orb = state.orb, amplitude = state.amplitude, size = 280.dp)
        Spacer(Modifier.height(24.dp))

        Text(state.status, style = MaterialTheme.typography.labelLarge, color = statusColor(state.orb))

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
                    color = if (state.orb == OrbState.Error) ErrorRed else Cyan,
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
        Icon(dest.icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(20.dp))
        Text(dest.label, style = MaterialTheme.typography.displayMedium, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text("COMING SOON", style = MaterialTheme.typography.labelSmall, color = Cyan)
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
            color = Cyan,
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
                    selectedIconColor = Cyan,
                    unselectedIconColor = Cyan,
                    selectedTextColor = TextPrimary,
                    unselectedTextColor = TextPrimary,
                ),
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

@Composable
private fun TasksCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceGlass)
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Column {
            todaysTasks.forEachIndexed { index, task ->
                TaskRow(task, taskAccents[index % taskAccents.size])
                if (index != todaysTasks.lastIndex) {
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = task.title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = task.time,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
    }
}

private fun statusColor(orb: OrbState): Color = when (orb) {
    OrbState.Listening -> Cyan
    OrbState.Thinking -> ElectricBlue
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
