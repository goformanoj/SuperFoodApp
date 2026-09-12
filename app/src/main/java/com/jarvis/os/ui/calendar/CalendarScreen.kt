package com.jarvis.os.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.os.calendar.AgendaFormat
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.ui.components.EmptyState
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.theme.Background
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

private const val DAY_MS = 24L * 60 * 60 * 1000

/** The next seven days from the device calendar — the same source JARVIS answers from. */
@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = remember { System.currentTimeMillis() }
    val agenda = remember { CalendarReader.agenda(context, days = 7) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Calendar",
            subtitle = "The next seven days. Ask JARVIS to add, move or cancel anything.",
        )

        when {
            agenda == null -> EmptyState(
                icon = Icons.Filled.CalendarMonth,
                title = "Calendar not connected",
                line = "Grant calendar access and your schedule shows up here.",
                modifier = Modifier.fillMaxWidth(),
            )
            agenda.isEmpty() -> EmptyState(
                icon = Icons.Filled.CalendarMonth,
                title = "Nothing in the next seven days",
                line = "Try: \"Hey JARVIS, add lunch with Priya tomorrow at 1pm\".",
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {
                WeekStrip(agenda, now)
                Spacer(Modifier.height(16.dp))

                AgendaFormat.nextUpIndex(agenda, now)?.let { i ->
                    UpNext(agenda[i], now)
                    Spacer(Modifier.height(18.dp))
                }

                var lastLabel = ""
                agenda.forEach { event ->
                    val label = AgendaFormat.dayLabel(event.startMillis, now)
                    if (label != lastLabel) {
                        lastLabel = label
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = JarvisTheme.accent)
                        Spacer(Modifier.height(8.dp))
                    }
                    EventCard(event)
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "Say \"Hey JARVIS\" to add, move or cancel an event by voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun WeekStrip(events: List<CalendarReader.Event>, now: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (offset in 0..6) {
            val dayMs = now + offset * DAY_MS
            val isToday = offset == 0
            val hasEvent = events.any { AgendaFormat.dayDifference(it.startMillis, now) == offset }
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isToday) JarvisTheme.accent else JarvisTheme.glass)
                    .border(1.dp, if (isToday) JarvisTheme.accent else JarvisTheme.glassBorder, shape)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    AgendaFormat.weekdayShort(dayMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) Background else TextSecondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    AgendaFormat.dayOfMonth(dayMs).toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isToday) Background else TextPrimary,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (!hasEvent) androidx.compose.ui.graphics.Color.Transparent
                            else if (isToday) Background else JarvisTheme.accent,
                        ),
                )
            }
        }
    }
}

@Composable
private fun UpNext(event: CalendarReader.Event, now: Long) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.accent.copy(alpha = 0.4f), shape)
            .padding(16.dp),
    ) {
        Text("Up next", style = MaterialTheme.typography.labelSmall, color = JarvisTheme.accent)
        Spacer(Modifier.height(4.dp))
        Text(
            "${event.title} · ${AgendaFormat.countdown(event.startMillis - now)}",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${AgendaFormat.dayLabel(event.startMillis, now)} · ${event.timeLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun EventCard(event: CalendarReader.Event) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 30.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(JarvisTheme.accent),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            event.title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            if (event.allDay) "All day" else event.timeLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = JarvisTheme.accent,
        )
    }
}
