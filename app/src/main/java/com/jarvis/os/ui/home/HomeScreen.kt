package com.jarvis.os.ui.home

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.HudOrb
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

private data class DrawerDest(val label: String, val icon: ImageVector)

private val drawerDestinations = listOf(
    DrawerDest("Home", Icons.Filled.Home),
    DrawerDest("Speech", Icons.Filled.Mic),
    DrawerDest("Chat", Icons.Filled.Forum),
    DrawerDest("Memory", Icons.Filled.Memory),
    DrawerDest("Files", Icons.Filled.Folder),
    DrawerDest("Calendar", Icons.Filled.CalendarMonth),
    DrawerDest("Vision", Icons.Filled.Visibility),
    DrawerDest("Automation", Icons.Filled.Bolt),
    DrawerDest("Skills", Icons.Filled.Extension),
    DrawerDest("Settings", Icons.Filled.Settings),
)

private data class Task(val title: String, val time: String, val accent: Color)

private val sampleTasks = listOf(
    Task("Team sync", "10:00", Cyan),
    Task("Finish physics assignment", "14:00", WarningOrange),
    Task("Call mentor", "18:30", SuccessGreen),
)

/**
 * Voice-first home. A menu button (top-left) opens the module drawer. The orb
 * fills the first screen; the schedule sits below it (scroll down to reach it).
 */
@Composable
fun VoiceHome(state: VoiceUiState, modifier: Modifier = Modifier) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            JarvisDrawer(onItemClick = { scope.launch { drawerState.close() } })
        },
    ) {
        Box(modifier = modifier.fillMaxSize().background(Background)) {
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

            // Menu button pinned to the top-left corner.
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
private fun HeroSection(state: VoiceUiState, height: androidx.compose.ui.unit.Dp) {
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

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
        HudOrb(amplitude = state.amplitude, size = 280.dp)
        Spacer(Modifier.height(24.dp))

        Text(state.status, style = MaterialTheme.typography.labelLarge, color = statusColor(state.orb))
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.transcript.ifBlank { "Listening for your command…" },
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
private fun JarvisDrawer(onItemClick: () -> Unit) {
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

        drawerDestinations.forEach { dest ->
            NavigationDrawerItem(
                label = { Text(dest.label, style = MaterialTheme.typography.titleMedium) },
                selected = dest.label == "Home",
                onClick = onItemClick,
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
            sampleTasks.forEachIndexed { index, task ->
                TaskRow(task)
                if (index != sampleTasks.lastIndex) {
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: Task) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(task.accent),
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
                reply = "It's sunny and 24 degrees.",
                amplitude = 0.5f,
            ),
        )
    }
}
