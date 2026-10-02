package com.jarvis.os.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.JarvisVoice
import com.jarvis.os.desktop.Telemetry
import com.jarvis.os.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Holo home: JARVIS as an interface laid over a city rather than a dashboard of cards. Panels are thin
 * lines and a wash of tinted glass; the numbers are all real (see JarvisVoice for the rule); the centre is the
 * same greeting, orb and quick commands every theme has. Only used on a wide window — a narrow one falls back
 * to the normal cockpit, which has no room for flanks.
 */

/** A line-art panel: tinted glass, a hairline frame, long rules on the top-left, tick marks at the opposite corner. */
@Composable
fun HoloPanel(title: String, modifier: Modifier = Modifier, code: String? = null, content: @Composable () -> Unit) {
    val accent = J.Accent
    Column(
        modifier.background(J.Glass).drawBehind {
            val s = 1.2.dp.toPx()
            drawRect(accent.copy(alpha = 0.16f), style = Stroke(1f))
            drawLine(accent.copy(alpha = 0.85f), Offset(0f, 0f), Offset(size.width * 0.58f, 0f), s)
            drawLine(accent.copy(alpha = 0.85f), Offset(0f, 0f), Offset(0f, size.height * 0.30f), s)
            drawLine(accent.copy(alpha = 0.45f), Offset(size.width, size.height), Offset(size.width - 34.dp.toPx(), size.height), s)
            drawLine(accent.copy(alpha = 0.45f), Offset(size.width, size.height), Offset(size.width, size.height - 20.dp.toPx()), s)
        }.padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), color = J.Text, fontSize = 10.sp, fontFamily = J.Mono, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
            if (code != null) Text(code, color = accent.copy(alpha = 0.7f), fontSize = 9.5.sp, fontFamily = J.Mono)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.22f)))
        Spacer(Modifier.height(9.dp))
        content()
    }
}

/** Across the top: the one-line state of the whole system, in the register of a flight display. */
@Composable
fun HoloStatusBar(a: DesktopAssistant, telemetry: Telemetry, modifier: Modifier = Modifier) {
    val up = ProxyClient.isConfigured()
    val parts = listOf(
        "JARVIS // " + if (up) "ONLINE" else "OFFLINE",
        "NODE " + BuildConfig.WORKER_URL.removePrefix("https://").substringBefore('.').uppercase(),
        "LAT " + (a.lastLatencyMs?.let { "$it MS" } ?: "—"),
        "PLAN " + a.plan.uppercase(),
        "UP " + Telemetry.clock(telemetry.uptimeMs()),
    )
    Row(modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("  ·  ", color = J.Accent.copy(alpha = 0.4f), fontSize = 10.5.sp, fontFamily = J.Mono)
            Text(p, color = if (i == 0) (if (up) J.Accent else J.Red) else J.TextMuted, fontSize = 10.5.sp, fontFamily = J.Mono, letterSpacing = 1.5.sp)
        }
    }
}

/** The date as a ring — the big day number inside a dial whose arc is the current minute — with the time beside it. */
@Composable
private fun HoloDateRing(modifier: Modifier = Modifier) {
    val now by rememberNow()
    val accent = J.Accent
    HoloPanel("Chrono", modifier, code = "01") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxHeight().fillMaxWidth()) {
                    val c = center
                    val r = size.minDimension / 2f - 4.dp.toPx()
                    drawCircle(accent.copy(alpha = 0.18f), r, c, style = Stroke(1.dp.toPx()))
                    for (i in 0 until 60) {
                        val a = (i * 6f - 90f) * (PI.toFloat() / 180f)
                        val long = i % 5 == 0
                        val r0 = r - (if (long) 7.dp.toPx() else 4.dp.toPx())
                        drawLine(accent.copy(alpha = if (long) 0.7f else 0.3f), Offset(c.x + cos(a) * r0, c.y + sin(a) * r0), Offset(c.x + cos(a) * r, c.y + sin(a) * r), 1.dp.toPx())
                    }
                    drawArc(accent, -90f, now.second * 6f, false, Offset(c.x - r + 9.dp.toPx(), c.y - r + 9.dp.toPx()), Size((r - 9.dp.toPx()) * 2, (r - 9.dp.toPx()) * 2), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(now.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)).uppercase(), color = J.TextMuted, fontSize = 10.sp, fontFamily = J.Mono, letterSpacing = 2.sp)
                    Text(now.format(DateTimeFormatter.ofPattern("dd")), color = J.Text, fontSize = 26.sp, fontFamily = J.Display)
                    Text(now.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).uppercase(), color = accent, fontSize = 10.sp, fontFamily = J.Mono, letterSpacing = 2.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = J.Text, fontSize = 24.sp, fontFamily = J.Display, maxLines = 1, softWrap = false)
                Text(now.format(DateTimeFormatter.ofPattern(":ss")), color = accent, fontSize = 13.sp, fontFamily = J.Display)
                Spacer(Modifier.height(4.dp))
                Text(ZoneId.systemDefault().id.uppercase(), color = J.TextDim, fontSize = 9.sp, fontFamily = J.Mono, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun gauge(label: String, value: String, fraction: Float): @Composable () -> Unit = {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(label, color = J.TextDim, fontSize = 9.5.sp, fontFamily = J.Mono, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text(value, color = J.Text, fontSize = 11.sp, fontFamily = J.Mono)
        }
        Spacer(Modifier.height(3.dp))
        Meter(fraction, Modifier.fillMaxWidth(), color = J.Accent)
    }
}

/** This laptop, in detail: processor with a minute of history, memory, disk, the JVM heap and thread count. */
@Composable
private fun HoloSysCore(telemetry: Telemetry, modifier: Modifier = Modifier) {
    val s = telemetry.latest
    val disk by produceState(0f) {
        while (true) {
            value = runCatching {
                val root = File(System.getProperty("user.home")).toPath().root?.toFile() ?: File("/")
                val total = root.totalSpace.toFloat()
                if (total > 0f) (total - root.usableSpace) / total else 0f
            }.getOrDefault(0f)
            delay(15_000)
        }
    }
    HoloPanel("Sys.core", modifier, code = "02") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(s?.cpu ?: 0f, if (s != null) Telemetry.percent(s.cpu) else "—", "CPU", size = 76.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PROC · 60 S", color = J.TextDim, fontSize = 9.sp, fontFamily = J.Mono, letterSpacing = 1.sp)
                Spacer(Modifier.height(3.dp))
                Sparkline(telemetry.cpuHistory, Modifier.fillMaxWidth().height(42.dp), capacity = Telemetry.HISTORY)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (s != null) gauge("MEMORY", Telemetry.gb(s.memUsedBytes, s.memTotalBytes), s.memUsedBytes.toFloat() / s.memTotalBytes.coerceAtLeast(1))()
        gauge("DISK", Telemetry.percent(disk), disk)()
    }
}

/** The link to the brain: status, the node, how long replies are really taking, and what's left of today's allowance. */
@Composable
private fun HoloComms(a: DesktopAssistant, modifier: Modifier = Modifier) {
    val latencies = remember { mutableStateListOf<Float>() }
    LaunchedEffect(a.lastLatencyMs) { a.lastLatencyMs?.let { latencies.add(it.toFloat()); if (latencies.size > 24) latencies.removeAt(0) } }
    val up = ProxyClient.isConfigured()
    HoloPanel("Comms", modifier, code = "03") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (up) J.Green else J.Red))
            Spacer(Modifier.width(8.dp))
            Text(if (up) "UPLINK ESTABLISHED" else "NO UPLINK", color = if (up) J.Green else J.Red, fontSize = 10.sp, fontFamily = J.Mono, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("REPLY LATENCY", color = J.TextDim, fontSize = 9.5.sp, fontFamily = J.Mono, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text(a.lastLatencyMs?.let { "$it ms" } ?: "—", color = J.Text, fontSize = 11.sp, fontFamily = J.Mono)
        }
        if (latencies.size >= 2) {
            val top = (latencies.max() * 1.15f).coerceAtLeast(1f)
            Sparkline(latencies.map { it / top }, Modifier.fillMaxWidth().height(30.dp), capacity = 24)
        }
        val u = a.usage
        if (u != null) {
            Spacer(Modifier.height(4.dp))
            gauge("ALLOWANCE", "${UsageStats.format(u.remaining)} left", u.remaining.toFloat() / u.cap.coerceAtLeast(1))()
        }
    }
}

/** The boot-log style readout: one dotted line per real fact, revealed top to bottom the first time it appears. */
@Composable
private fun HoloLog(a: DesktopAssistant, telemetry: Telemetry, modifier: Modifier = Modifier) {
    val s = telemetry.latest
    val lines = JarvisVoice.logLines(
        JarvisVoice.Snapshot(
            uplink = ProxyClient.isConfigured(),
            node = BuildConfig.WORKER_URL.removePrefix("https://").substringBefore('.'),
            latencyMs = a.lastLatencyMs?.toInt(), plan = a.plan, facts = a.facts.size, conversations = a.conversations.size,
            cpuPercent = s?.let { (it.cpu * 100).roundToInt() },
            memUsedGb = s?.let { it.memUsedBytes / 1e9 }, memTotalGb = s?.let { it.memTotalBytes / 1e9 },
            uptime = Telemetry.clock(telemetry.uptimeMs()),
        ),
    )
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (shown < lines.size) { delay(170); shown++ } }
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "c")
    HoloPanel("System log", modifier, code = "04") {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            lines.take(shown).forEach { Text(it, color = J.TextMuted, fontSize = 10.sp, fontFamily = J.Mono, maxLines = 1, overflow = TextOverflow.Clip) }
            Text("> awaiting command" + if (blink < 0.5f) " █" else "  ", color = J.Accent, fontSize = 10.sp, fontFamily = J.Mono)
        }
    }
}

/** A slowly turning reactor emblem, the one purely decorative thing here — and it carries no number to be false. */
@Composable
private fun HoloEmblem(modifier: Modifier = Modifier) {
    val accent = J.Accent
    val warm = LocalPalette.current.highlight
    val spin by rememberInfiniteTransition(label = "emblem").animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "s")
    Canvas(modifier.size(120.dp)) {
        val c = center
        val r = size.minDimension / 2f - 2.dp.toPx()
        drawCircle(accent.copy(alpha = 0.25f), r, c, style = Stroke(1.dp.toPx()))
        rotate(spin, c) {
            for (i in 0 until 10) drawArc(accent.copy(alpha = 0.75f), i * 36f + 3f, 26f, false, Offset(c.x - r * 0.78f, c.y - r * 0.78f), Size(r * 1.56f, r * 1.56f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Butt))
        }
        rotate(-spin * 1.6f, c) { drawArc(warm, 0f, 70f, false, Offset(c.x - r * 0.55f, c.y - r * 0.55f), Size(r * 1.1f, r * 1.1f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
        drawCircle(accent.copy(alpha = 0.55f), r * 0.34f, c, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = 0.85f), r * 0.14f, c)
        drawCircle(accent.copy(alpha = 0.35f), r * 0.26f, c)
    }
}

/** The two flanks of the Holo home. */
@Composable
fun HoloLeft(a: DesktopAssistant, telemetry: Telemetry, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HoloDateRing(Modifier.fillMaxWidth())
        HoloSysCore(telemetry, Modifier.fillMaxWidth())
        HoloComms(a, Modifier.fillMaxWidth())
    }
}

@Composable
fun HoloRight(a: DesktopAssistant, telemetry: Telemetry, onMemory: () -> Unit, onTasks: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HoloLog(a, telemetry, Modifier.fillMaxWidth())
        TodayPanel(a, onTasks, Modifier.fillMaxWidth())
        MemoryPanel(a, onMemory, Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HoloEmblem() }
    }
}
