package com.jarvis.os.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.BreathingOrb
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.ElectricBlue
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SuccessGreen
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import com.jarvis.os.ui.theme.WarningOrange
import java.util.Calendar

private data class Module(val label: String, val icon: ImageVector)

private data class Task(val title: String, val time: String, val accent: Color)

private val modules = listOf(
    Module("Voice", Icons.Filled.Mic),
    Module("Chat", Icons.Filled.Chat),
    Module("Vision", Icons.Filled.Visibility),
    Module("Memory", Icons.Filled.Memory),
    Module("Files", Icons.Filled.Folder),
    Module("Calendar", Icons.Filled.CalendarMonth),
    Module("Automate", Icons.Filled.Bolt),
    Module("Settings", Icons.Filled.Settings),
)

private val sampleTasks = listOf(
    Task("Team sync", "10:00", Cyan),
    Task("Finish physics assignment", "14:00", WarningOrange),
    Task("Call mentor", "18:30", SuccessGreen),
)

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val greeting = remember { greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    Box(modifier = modifier.fillMaxSize().background(Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            TopBar()
            Spacer(Modifier.height(28.dp))

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

            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BreathingOrb(size = 230.dp)
            }
            Spacer(Modifier.height(28.dp))

            SpeakButton(modifier = Modifier.align(Alignment.CenterHorizontally))

            Spacer(Modifier.height(32.dp))
            SectionHeader("Today's Tasks")
            Spacer(Modifier.height(12.dp))
            TasksCard()

            Spacer(Modifier.height(28.dp))
            SectionHeader("Modules")
            Spacer(Modifier.height(14.dp))
            ModuleGrid()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = "Menu",
            tint = TextPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { }
                .padding(4.dp)
                .size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "JARVIS OS",
            style = MaterialTheme.typography.labelLarge,
            color = Cyan,
        )
        Spacer(Modifier.weight(1f))
        StatusPill()
    }
}

@Composable
private fun StatusPill() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(SurfaceGlass)
            .border(BorderStroke(1.dp, GlassBorder), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(SuccessGreen),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "ONLINE",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun SpeakButton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    listOf(Cyan.copy(alpha = 0.16f), ElectricBlue.copy(alpha = 0.16f)),
                ),
            )
            .border(BorderStroke(1.5.dp, Cyan.copy(alpha = 0.7f)), CircleShape)
            .clickable { }
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = Cyan,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Speak",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
    )
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceGlass)
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        content()
    }
}

@Composable
private fun TasksCard() {
    GlassCard {
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
        Spacer(Modifier.width(14.dp))
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

@Composable
private fun ModuleGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        modules.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { module ->
                    ModuleTile(module, Modifier.weight(1f))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModuleTile(module: Module, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceGlass)
            .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(16.dp))
            .clickable { }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = module.icon,
            contentDescription = module.label,
            tint = Cyan,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = module.label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun greetingForHour(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
