package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.brain.TaskDates
import java.time.ZoneId

/**
 * Scheduled (AGENT_PLAN §6/§7): what JARVIS will do by itself. Routines ("every weekday at
 * 8, brief me") and one-off reminders, each with its next time, and the controls to pause,
 * mute, run now or remove. Both are created by just asking JARVIS in chat.
 */
@Composable
fun ScheduledScreen(a: DesktopAssistant, onOpenChat: () -> Unit) {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    Page(
        "Routines",
        "What JARVIS does by itself. Ask in chat — “every weekday at 8, brief me on my day”, “remind me at 6 to call Mom” — and it appears here. Routines run while JARVIS is in the tray; results pop up as notifications.",
    ) {
        LazyColumn(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { HudRule("Routines · ${a.routines.size}", Modifier.padding(bottom = 4.dp)) }
            if (a.routines.isEmpty()) item {
                Text("No routines yet. Try: “every weekday at 8, brief me: my tasks, reminders and the weather”.", color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            items(a.routines, key = { it.id }) { r -> RoutineRow(a, r, now, zone, onOpenChat) }

            item { HudRule("Reminders · ${a.upcomingReminders.size}", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
            if (a.upcomingReminders.isEmpty()) item {
                Text("No reminders. Try: “remind me at 5 to call the bank”.", color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            items(a.upcomingReminders, key = { it.id }) { rem ->
                Row(
                    Modifier.fillMaxWidth().clip(HudShapeSmall).background(J.Card).border(1.dp, J.CardBorder, HudShapeSmall)
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.NotificationsNone, null, tint = J.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(rem.text, color = J.Text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(TaskDates.label(rem.at, now, zone), color = if (rem.at < now) Color(0xFFFF8A8A) else J.TextMuted, fontSize = 12.sp)
                    Hint("Cancel this reminder") {
                        Icon(Icons.Outlined.Close, "Cancel", tint = J.TextDim, modifier = Modifier.size(32.dp).clip(HudShapeSmall).clicky { a.deleteReminder(rem.id) }.padding(7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(a: DesktopAssistant, r: Brain.Routine, now: Long, zone: ZoneId, onOpenChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(HudShapeSmall).background(J.Card)
            .border(1.dp, if (r.enabled) J.CardBorder else J.Hairline, HudShapeSmall)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(J.Accent.copy(alpha = if (r.enabled) 0.14f else 0.05f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Repeat, null, tint = if (r.enabled) J.Accent else J.TextFaint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, color = if (r.enabled) J.Text else J.TextDim, fontSize = 14.sp)
                Spacer(Modifier.width(10.dp))
                Text(r.schedule.describe(), color = J.Accent, fontSize = 12.sp)
            }
            Text(r.instruction, color = J.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (r.enabled) "Next: ${TaskDates.label(r.nextRun, now, zone)}" + (r.lastRun?.let { " · last ran ${TaskDates.label(it, now, zone)}" } ?: "") else "Paused",
                color = J.TextFaint, fontSize = 11.sp,
            )
        }
        Hint(if (a.thinking) "JARVIS is busy — try in a moment" else "Run it now (opens the result)") {
            Icon(Icons.Outlined.PlayArrow, "Run now", tint = J.Accent,
                modifier = Modifier.size(32.dp).clip(CircleShape).clicky { a.runRoutineNow(r.id); onOpenChat() }.padding(6.dp))
        }
        Hint(if (r.speak) "Spoken aloud when it runs — click to only notify" else "Notification only — click to also speak it") {
            Icon(if (r.speak) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, "Speak", tint = if (r.speak) J.Accent else J.TextFaint,
                modifier = Modifier.size(32.dp).clip(CircleShape).clicky { a.setRoutineSpeak(r.id, !r.speak) }.padding(7.dp))
        }
        Hint(if (r.enabled) "Pause this routine" else "Turn it back on") {
            Text(
                if (r.enabled) "ON" else "OFF", color = if (r.enabled) J.OnAccent else J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 4.dp).clip(HudShapeSmall).background(if (r.enabled) J.Accent else Color.Transparent)
                    .border(1.dp, if (r.enabled) J.Accent else J.Border, HudShapeSmall).clicky { a.setRoutineEnabled(r.id, !r.enabled) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Hint("Remove this routine") {
            Icon(Icons.Outlined.Close, "Remove", tint = J.TextDim, modifier = Modifier.size(32.dp).clip(HudShapeSmall).clicky { a.deleteRoutine(r.id) }.padding(7.dp))
        }
    }
}
