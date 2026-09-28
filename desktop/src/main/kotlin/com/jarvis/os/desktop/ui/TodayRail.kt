package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.desktop.DesktopAssistant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "Today" rail from the design. Only real data: the date, what JARVIS remembers.
 * Tasks and routines show honest empty states until their phases land — never
 * sample content dressed up as the user's day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayRail(a: DesktopAssistant, onMemory: () -> Unit) {
    Column(
        Modifier.width(320.dp).fillMaxHeight().background(J.Rail).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        Column {
            Eyebrow("Today")
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                color = J.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Section("Running") { EmptyNote("No tasks running. Laptop tasks arrive in Phase 3.") }
        Section("Coming up") { EmptyNote("Reminders and routines arrive in Phase 4 — “every weekday at 8, brief me”.") }
        Section("Remembered") {
            if (a.facts.isEmpty()) {
                EmptyNote("Nothing yet. Say “remember that …” and JARVIS keeps it.")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.facts.take(12).forEach { f ->
                        Text(
                            f, color = Color(0xFFC3CEDD), fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x0DFFFFFF))
                                .border(1.dp, J.Hairline, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Text(
                    "Manage in Memory", color = J.Cyan, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onMemory).padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = J.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text, color = J.TextDim, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, J.Hairline, RoundedCornerShape(12.dp)).padding(14.dp),
    )
}
