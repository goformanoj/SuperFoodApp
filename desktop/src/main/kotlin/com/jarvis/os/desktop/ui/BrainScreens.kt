package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.brain.Brain
import com.jarvis.os.desktop.brain.TaskDates
import com.jarvis.os.memory.MemoryFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// Tasks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TasksScreen(a: DesktopAssistant, onOpenConversation: (String) -> Unit) {
    Page("Tasks", "Your to-dos. Add them here, or just tell JARVIS — “add call the bank for tomorrow at 5”. Tasks it creates link back to the chat they came from.") {
        AddTaskRow(a)
        Spacer(Modifier.height(18.dp))
        val now = System.currentTimeMillis()
        val groups = a.openTasks.groupBy { TaskDates.bucket(it.dueAt, now) }
        LazyColumn(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (a.openTasks.isEmpty()) item {
                Text("Nothing on your list. Type one above, or tell JARVIS: “add call the bank to my to-dos for tomorrow”.", color = J.TextMuted, fontSize = 14.sp)
            }
            TaskDates.Bucket.entries.forEach { bucket ->
                val tasks = groups[bucket].orEmpty()
                if (tasks.isNotEmpty()) {
                    item(key = "h-$bucket") {
                        HudRule(
                            "${bucket.label} · ${tasks.size}",
                            Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(tasks, key = { it.id }) { t -> TaskRow(a, t, now, overdue = bucket == TaskDates.Bucket.OVERDUE, onOpenConversation) }
                }
            }
            if (a.doneTasks.isNotEmpty()) {
                item(key = "h-done") { HudRule("Done · ${a.doneTasks.size}", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
                items(a.doneTasks, key = { "d-" + it.id }) { t -> TaskRow(a, t, now, overdue = false, onOpenConversation) }
            }
        }
    }
}

@Composable
private fun AddTaskRow(a: DesktopAssistant) {
    var text by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf<TaskDates.Quick?>(null) }
    fun add() {
        if (text.isBlank()) return
        a.addTask(text, quick?.let { TaskDates.quick(it, System.currentTimeMillis()) })
        text = ""; quick = null
    }
    Row(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(44.dp).clip(HudShapeSmall).background(J.Card)
                .border(1.dp, J.Accent.copy(alpha = 0.35f), HudShapeSmall).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text("Add a task…", color = J.TextFaint, fontSize = 14.sp)
            BasicTextField(
                value = text, onValueChange = { text = it.replace("\n", "") }, singleLine = true,
                textStyle = TextStyle(color = J.Text, fontSize = 14.sp), cursorBrush = SolidColor(J.Accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { add(); true } else false
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        TaskDates.Quick.entries.forEach { q ->
            val on = quick == q
            Text(
                q.label, color = if (on) J.OnAccent else J.TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(end = 6.dp).clip(HudShapeSmall)
                    .background(if (on) J.Accent else Color.Transparent).border(1.dp, J.Border, HudShapeSmall)
                    .clicky { quick = if (on) null else q }.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        Text(
            "ADD", color = J.OnAccent, fontSize = 11.sp, fontFamily = J.Display, letterSpacing = 1.5.sp,
            modifier = Modifier.clip(HudShapeSmall).background(if (text.isBlank()) J.Accent.copy(alpha = 0.35f) else J.Accent)
                .clicky { add() }.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun TaskRow(a: DesktopAssistant, t: Brain.Task, now: Long, overdue: Boolean, onOpenConversation: (String) -> Unit) {
    val done = t.status == Brain.TaskStatus.DONE
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(HudShapeSmall).background(J.Card).border(1.dp, J.CardBorder, HudShapeSmall)
            .hoverable(hover).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (done) J.Green else Color.Transparent)
                .border(1.5.dp, if (done) J.Green else J.Accent.copy(alpha = 0.6f), CircleShape)
                .clicky { a.setTaskDone(t.id, !done) },
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Outlined.Check, "Done", tint = Color.Black, modifier = Modifier.size(14.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, color = if (done) J.TextDim else J.Text, fontSize = 14.sp,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            val sub = listOfNotNull(
                t.dueAt?.let { TaskDates.label(it, now) },
                t.notes,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = if (overdue) Color(0xFFFF8A8A) else J.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        t.sourceConversation?.let { src ->
            Text(
                "FROM CHAT ›", color = J.Accent, fontSize = 9.5.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 8.dp).clicky { onOpenConversation(src) },
            )
        }
        if (hovered) Hint("Delete task") {
            Icon(Icons.Outlined.Close, "Delete task", tint = J.TextDim, modifier = Modifier.size(28.dp).clip(HudShapeSmall).clicky { a.deleteTask(t.id) }.padding(6.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Memory — typed cards, each with where it came from
// ─────────────────────────────────────────────────────────────────────────────

private val kindTitles = linkedMapOf(
    Brain.MemoryKind.PROFILE to "About you",
    Brain.MemoryKind.PERSON to "People",
    Brain.MemoryKind.PLACE to "Places",
    Brain.MemoryKind.PROJECT to "Projects",
    Brain.MemoryKind.INSTRUCTION to "Standing instructions",
    Brain.MemoryKind.FACT to "Remembered",
)

@Composable
fun MemoryScreen(a: DesktopAssistant, onOpenConversation: (String) -> Unit) {
    Page("Memory", "What JARVIS keeps about you, grouped by kind, each with the chat it came from. It rides on every message, so keep it to what matters. Stored on this laptop.") {
        if (a.memories.isEmpty()) {
            Text("Nothing remembered yet. In chat, say “remember that …”.", color = J.TextMuted, fontSize = 15.sp)
            return@Page
        }
        val byKind = a.memories.groupBy { it.kind }
        LazyColumn(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kindTitles.forEach { (kind, title) ->
                val items = byKind[kind].orEmpty()
                if (items.isNotEmpty()) {
                    item(key = "h-$kind") { HudRule("$title · ${items.size}", Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                    items(items, key = { it.id }) { m ->
                        Row(
                            Modifier.fillMaxWidth().clip(HudShapeSmall).background(J.Card).border(1.dp, J.CardBorder, HudShapeSmall)
                                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                // Masked like the phone: a remembered number never sits in plain view.
                                Text(MemoryFormat.masked(m.text), color = J.Text, fontSize = 14.sp)
                                val source = m.sourceConversation?.let { id -> a.conversations.firstOrNull { it.id == id }?.title }
                                Row {
                                    Text(dayLabel(m.created), color = J.TextDim, fontSize = 11.sp)
                                    if (source != null) {
                                        Text("  ·  from “$source”", color = J.Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clicky { onOpenConversation(m.sourceConversation!!) })
                                    }
                                }
                            }
                            Hint("Forget this") {
                                Icon(Icons.Outlined.Close, "Forget this", tint = J.TextDim,
                                    modifier = Modifier.size(32.dp).clip(HudShapeSmall).clicky { a.forgetMemory(m.id) }.padding(7.dp))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Forget everything", color = Color(0xFFFF8A8A), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 14.dp).clip(HudShapeSmall).clicky { a.forgetEverything() }.padding(8.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Activity — everything JARVIS did
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ActivityScreen(a: DesktopAssistant) {
    val items = remember(a.openTasks, a.memories, a.conversations) { a.activity() }
    Page("Activity", "Everything JARVIS did on this laptop, newest first. Nothing happens behind your back.") {
        if (items.isEmpty()) {
            Text("No activity yet.", color = J.TextMuted, fontSize = 15.sp)
            return@Page
        }
        LazyColumn(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.id }) { ev ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(timeLabel(ev.at), color = J.TextDim, fontSize = 12.sp, fontFamily = J.Mono, modifier = Modifier.width(150.dp))
                    Text(ev.kind.uppercase(), color = J.Accent, fontSize = 9.5.sp, fontFamily = J.Display, letterSpacing = 1.2.sp, modifier = Modifier.width(96.dp))
                    Text(ev.summary, color = J.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search — one box over everything (Ctrl+K)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SearchOverlay(a: DesktopAssistant, onOpen: (Brain.SearchHit) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q) { if (q.isBlank()) emptyList() else a.search(q) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clicky(onClose),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 90.dp).widthIn(max = 680.dp).fillMaxWidth().clip(HudShape).background(J.Card)
                .border(1.dp, J.Accent.copy(alpha = 0.5f), HudShape).hudBrackets(J.Accent, inset = 3.dp)
                .clicky { } // swallow clicks inside the panel
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = J.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search chats, tasks, notes and memory…", color = J.TextFaint, fontSize = 16.sp)
                    BasicTextField(
                        value = q, onValueChange = { q = it.replace("\n", "") }, singleLine = true,
                        textStyle = TextStyle(color = J.Text, fontSize = 16.sp), cursorBrush = SolidColor(J.Accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                            when {
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.Escape -> { onClose(); true }
                                e.key == Key.Enter && hits.isNotEmpty() -> { onOpen(hits.first()); true }
                                else -> false
                            }
                        },
                    )
                }
                Text("ESC", color = J.TextFaint, fontSize = 10.sp, fontFamily = J.Mono)
            }
            if (q.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(J.Hairline))
                Spacer(Modifier.height(6.dp))
                if (hits.isEmpty()) {
                    Text("Nothing found for “$q”.", color = J.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(hits, key = { it.kind + it.refId }) { h ->
                            Row(
                                Modifier.fillMaxWidth().clip(HudShapeSmall).clicky { onOpen(h) }.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(iconFor(h.kind), null, tint = J.Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (h.kind == "memory") MemoryFormat.masked(h.snippet.replace("[", "").replace("]", "")) else h.title,
                                        color = J.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (h.kind != "memory" && h.snippet.isNotBlank()) {
                                        Text(h.snippet.replace("[", "").replace("]", ""), color = J.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Text(h.kind.uppercase(), color = J.TextFaint, fontSize = 9.sp, fontFamily = J.Display, letterSpacing = 1.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    "conversation", "message" -> Icons.Outlined.ChatBubbleOutline
    "task" -> Icons.Outlined.TaskAlt
    "note" -> Icons.Outlined.Description
    else -> Icons.Outlined.AutoAwesome
}

// ─────────────────────────────────────────────────────────────────────────────
// Today — the cockpit panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TodayPanel(a: DesktopAssistant, onOpenTasks: () -> Unit, modifier: Modifier = Modifier) {
    val now = System.currentTimeMillis()
    val due = a.openTasks.filter { it.dueAt != null && it.dueAt < TaskDates.endOfDay(now) }
    HudPanel("Today", modifier, code = "00") {
        if (due.isEmpty()) {
            Text(
                if (a.openTasks.isEmpty()) "Nothing due. Tell JARVIS what you need to do."
                else "Nothing due today · ${a.openTasks.size} open",
                color = J.TextDim, fontSize = 12.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                due.take(5).forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).border(1.dp, J.Accent, CircleShape).clicky { a.setTaskDone(t.id, true) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(t.title, color = if ((t.dueAt ?: 0) < now) Color(0xFFFF8A8A) else J.Text, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(TaskDates.label(t.dueAt!!, now).removePrefix("Today "), color = J.TextDim, fontSize = 10.sp, fontFamily = J.Mono)
                    }
                }
                if (due.size > 5) Text("+ ${due.size - 5} more", color = J.TextDim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("ALL TASKS ›", color = J.Accent, fontSize = 9.5.sp, fontFamily = J.Display, letterSpacing = 1.5.sp, modifier = Modifier.clicky(onOpenTasks))
    }
}

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun dayLabel(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
private fun timeLabel(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
