package com.jarvis.os.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.calendar.CalendarReader
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.Cyan
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The real device calendar, grouped by day — the same source JARVIS answers from. */
@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val agenda = remember { CalendarReader.agenda(context, days = 7) }
    val dayFormat = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Calendar",
            subtitle = "The next seven days, straight from your device calendar. Ask JARVIS " +
                "to add, move or cancel anything here.",
        )

        when {
            // These mean different things and must not look the same.
            agenda == null -> Note("Calendar access hasn't been granted, so there's nothing to show yet.")
            agenda.isEmpty() -> Note("Nothing scheduled in the next seven days.")
            else -> {
                var lastDay = ""
                agenda.forEach { event ->
                    val day = dayFormat.format(Date(event.startMillis))
                    if (day != lastDay) {
                        lastDay = day
                        Spacer(Modifier.height(14.dp))
                        Text(day, style = MaterialTheme.typography.labelLarge, color = JarvisTheme.accent)
                        Spacer(Modifier.height(8.dp))
                    }
                    EventCard(event)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
}

@Composable
private fun EventCard(event: CalendarReader.Event) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(JarvisTheme.glass)
            .border(1.dp, JarvisTheme.glassBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
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
        Text(event.timeLabel(), style = MaterialTheme.typography.labelLarge, color = JarvisTheme.accent)
    }
}
