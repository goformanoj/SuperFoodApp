package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.Telemetry
import com.jarvis.os.memory.MemoryFormat
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun rememberNow() = produceState(LocalDateTime.now()) {
    while (true) { value = LocalDateTime.now(); delay(1000) }
}

/** Time, date and how long JARVIS has been running. */
@Composable
fun ChronoPanel(telemetry: Telemetry, modifier: Modifier = Modifier) {
    val now by rememberNow()
    HudPanel("Chrono", modifier, code = "01") {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = J.Text, fontSize = 38.sp, fontFamily = J.Display, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(now.format(DateTimeFormatter.ofPattern(":ss")), color = J.Accent, fontSize = 14.sp, fontFamily = J.Display)
            Spacer(Modifier.width(10.dp))
            Text(now.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())).uppercase(), color = J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(10.dp))
        Readout("Uptime", Telemetry.clock(telemetry.uptimeMs()))
    }
}

/** This laptop, live: CPU with a minute of history, and memory. */
@Composable
fun SystemPanel(telemetry: Telemetry, modifier: Modifier = Modifier) {
    val s = telemetry.latest
    HudPanel("System", modifier, code = "02") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(s?.cpu ?: 0f, if (s != null) Telemetry.percent(s.cpu) else "—", "CPU", size = 78.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("CPU · 60 S", color = J.TextDim, fontSize = 9.sp, fontFamily = J.Display, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Sparkline(telemetry.cpuHistory, Modifier.fillMaxWidth().height(44.dp), capacity = Telemetry.HISTORY)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (s != null) {
            Readout("Memory", Telemetry.gb(s.memUsedBytes, s.memTotalBytes))
            Spacer(Modifier.height(4.dp))
            Meter(s.memUsedBytes.toFloat() / s.memTotalBytes.coerceAtLeast(1), Modifier.fillMaxWidth(), color = LocalHighlight())
        } else {
            Readout("Memory", "sampling…")
        }
    }
}

@Composable
private fun LocalHighlight(): Color = com.jarvis.os.ui.theme.LocalPalette.current.highlight

/** The link to the brain: server, identity, allowance, real reply latency. */
@Composable
fun LinkPanel(a: DesktopAssistant, modifier: Modifier = Modifier) {
    HudPanel("Link", modifier, code = "03") {
        val up = ProxyClient.isConfigured()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (up) J.Green else J.Red))
            Spacer(Modifier.width(8.dp))
            Text(if (up) "UPLINK ESTABLISHED" else "NO UPLINK", color = if (up) J.Green else J.Red, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(8.dp))
        Readout("Node", BuildConfig.WORKER_URL.removePrefix("https://").substringBefore('.'))
        Readout("Plan", a.plan.uppercase())
        Readout("Latency", a.lastLatencyMs?.let { "$it ms" } ?: "—")
        val u = a.usage
        if (u != null) {
            Readout("Allowance", "${UsageStats.format(u.remaining)} left")
            Spacer(Modifier.height(4.dp))
            Meter(u.remaining.toFloat() / u.cap.coerceAtLeast(1), Modifier.fillMaxWidth())
        } else {
            Readout("Allowance", "after first reply")
        }
    }
}

/** What JARVIS remembers — masked like the phone's memory screen. */
@Composable
fun MemoryPanel(a: DesktopAssistant, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    HudPanel("Memory core", modifier, code = "04") {
        Readout("Facts stored", a.facts.size.toString(), valueColor = J.Accent)
        Spacer(Modifier.height(6.dp))
        if (a.facts.isEmpty()) {
            Text("Say “remember that …”", color = J.TextDim, fontSize = 12.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.facts.takeLast(3).reversed().forEach {
                    Text("› " + MemoryFormat.masked(it), color = J.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("OPEN MEMORY ›", color = J.Accent, fontSize = 9.5.sp, fontFamily = J.Display, letterSpacing = 1.5.sp, modifier = Modifier.clicky(onClick = onOpen).padding(vertical = 2.dp))
    }
}

/** Conversations on this laptop. */
@Composable
fun SessionPanel(a: DesktopAssistant, modifier: Modifier = Modifier) {
    HudPanel("Sessions", modifier, code = "05") {
        Readout("Conversations", a.conversations.size.toString())
        Readout("Messages", a.messageCount.toString())
        Readout("Device", "This laptop")
        Readout("Voice", "Ctrl+Space")
    }
}

/** Sections of the roadmap still to land — stated plainly, not faked. */
@Composable
fun ModulesPanel(modifier: Modifier = Modifier) {
    HudPanel("Modules", modifier, code = "06") {
        listOf("Chat" to true, "Memory" to true, "Voice" to true, "Wake word" to true, "Tasks" to true, "Search" to true, "Agent tools" to true, "Reminders" to true).forEach { (name, on) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (on) J.Green else J.TextFaint))
                Spacer(Modifier.width(10.dp))
                Text(name.uppercase(), color = if (on) J.Text else J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                Text(if (on) "ONLINE" else "STANDBY", color = if (on) J.Green else J.TextFaint, fontSize = 10.sp, fontFamily = J.Mono)
            }
        }
    }
}

/** Home's one side rail for the standard themes: what is due, what is left today, how the laptop is doing. */
@Composable
fun LeanRail(a: DesktopAssistant, telemetry: Telemetry, onTasks: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TodayPanel(a, onTasks, Modifier.fillMaxWidth())
        AllowancePanel(a, Modifier.fillMaxWidth())
        LaptopPanel(telemetry, Modifier.fillMaxWidth())
    }
}

/** The token allowance for today: the number that matters, and a bar. Same figures as the account card. */
@Composable
fun AllowancePanel(a: DesktopAssistant, modifier: Modifier = Modifier) {
    HudPanel("Allowance", modifier) {
        val u = a.usage
        if (u != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(compactTokens(u.remaining), color = J.Text, fontSize = 24.sp, fontFamily = J.Display)
                Spacer(Modifier.width(8.dp))
                Text("of ${compactTokens(u.cap)} tokens left", color = J.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(10.dp))
            Meter(u.remaining.toFloat() / u.cap.coerceAtLeast(1), Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("${a.plan.replaceFirstChar { it.uppercase() }} plan · refills daily", color = J.TextDim, fontSize = 11.sp)
        } else {
            Text("Checking your allowance…", color = J.TextDim, fontSize = 12.sp)
        }
    }
}

/** This laptop, briefly: processor and memory as two bars. */
@Composable
fun LaptopPanel(telemetry: Telemetry, modifier: Modifier = Modifier) {
    HudPanel("This laptop", modifier) {
        val s = telemetry.latest
        if (s != null) {
            Readout("CPU", Telemetry.percent(s.cpu))
            Meter(s.cpu, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Readout("Memory", Telemetry.gb(s.memUsedBytes, s.memTotalBytes))
            Meter(s.memUsedBytes.toFloat() / s.memTotalBytes.coerceAtLeast(1), Modifier.fillMaxWidth(), color = LocalHighlight())
        } else {
            Text("Sampling…", color = J.TextDim, fontSize = 12.sp)
        }
    }
}
