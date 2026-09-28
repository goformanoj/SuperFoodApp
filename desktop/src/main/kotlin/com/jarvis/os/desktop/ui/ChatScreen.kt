package com.jarvis.os.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.DesktopTurn
import com.jarvis.os.desktop.Markdown
import com.jarvis.os.desktop.Telemetry
import com.jarvis.os.desktop.agent.ActionCard
import com.jarvis.os.voice.OrbState
import java.time.LocalTime

/** The orb's state is the app's REAL state — never decoration. */
private fun orbStateOf(a: DesktopAssistant): OrbState = when {
    !a.configured -> OrbState.Offline
    a.voice == DesktopAssistant.Voice.Listening -> OrbState.Listening
    a.voice == DesktopAssistant.Voice.Speaking -> OrbState.Speaking
    a.voice == DesktopAssistant.Voice.Transcribing -> OrbState.Thinking
    a.thinkingHere -> OrbState.Thinking
    a.error != null -> OrbState.Error
    else -> OrbState.Idle
}

private fun statusOf(a: DesktopAssistant): String = when {
    !a.configured -> "Not connected"
    a.voice == DesktopAssistant.Voice.Listening -> "Listening"
    a.voice == DesktopAssistant.Voice.Transcribing -> "Transcribing"
    a.voice == DesktopAssistant.Voice.Speaking -> "Speaking · click the mic to interrupt"
    a.thinkingHere -> "Thinking"
    a.error != null -> "Something went wrong"
    else -> "Online · ready"
}

/** Home: the orb is the hero, exactly as on the phone. */
fun hasConversation(a: DesktopAssistant) = a.turns.isNotEmpty() || a.thinkingHere

@Composable
fun ChatScreen(
    a: DesktopAssistant,
    telemetry: Telemetry,
    text: MutableState<String>,
    focus: FocusRequester,
    onMemory: () -> Unit,
    onTasks: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (hasConversation(a)) {
            ChatHeader(a)
            Box(Modifier.weight(1f).fillMaxWidth()) { Conversation(a) }
        } else {
            Cockpit(a, telemetry, text, Modifier.weight(1f), onMemory, onTasks)
        }
        a.error?.let { ErrorBanner(it) }
        Composer(a, text, focus)
    }
}

/**
 * Home: the cockpit. The reactor is the hero, flanked by instrument panels — every
 * number on them real (this laptop, the uplink, memory, sessions). Narrow windows
 * drop the flanks and keep the reactor.
 */
@Composable
private fun Cockpit(
    a: DesktopAssistant,
    telemetry: Telemetry,
    text: MutableState<String>,
    modifier: Modifier,
    onMemory: () -> Unit,
    onTasks: () -> Unit,
) {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 960.dp
        val orbSize = (minOf(maxWidth - if (wide) 520.dp else 40.dp, maxHeight - 250.dp)).coerceIn(240.dp, 460.dp)
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            if (wide) Column(Modifier.width(236.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChronoPanel(telemetry, Modifier.fillMaxWidth())
                SystemPanel(telemetry, Modifier.fillMaxWidth())
                LinkPanel(a, Modifier.fillMaxWidth())
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(greeting.uppercase(), color = J.Text, fontSize = 22.sp, fontFamily = J.Display, letterSpacing = 4.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("How can I help you today?", color = J.TextMuted, fontSize = 14.sp)
                ReactorOrb(size = orbSize, state = orbStateOf(a))
                StatusLine(a)
                Spacer(Modifier.height(10.dp))
                WakeChip(a)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Plan my day", "Explain it simply", "Remember something").forEach { s -> Suggestion(s) { text.value = suggestionText(s) } }
                }
            }
            if (wide) Column(Modifier.width(236.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TodayPanel(a, onTasks, Modifier.fillMaxWidth())
                MemoryPanel(a, onMemory, Modifier.fillMaxWidth())
                ModulesPanel(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun suggestionText(s: String) = when (s) {
    "Plan my day" -> "Plan my day around my priorities: "
    "Explain it simply" -> "Explain this in plain words: "
    else -> "Remember that "
}

@Composable
private fun StatusLine(a: DesktopAssistant) {
    val color = when {
        !a.configured || a.error != null -> Color(0xFFFF8A8A)
        else -> J.Accent
    }
    Text(statusOf(a).uppercase(), color = color, fontSize = 12.sp, fontFamily = J.Display, letterSpacing = 3.sp)
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    Text(
        text, color = J.TextMuted, fontSize = 13.sp,
        modifier = Modifier.clip(HudShapeSmall).background(J.Glass)
            .border(1.dp, J.Accent.copy(alpha = 0.35f), HudShapeSmall).clicky(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The chat's title: click to rename, Enter saves, Esc or clicking away keeps the old one. */
@Composable
private fun EditableTitle(a: DesktopAssistant) {
    val conv = a.active
    var editing by remember(conv?.id) { mutableStateOf(false) }
    var draft by remember(conv?.id) { mutableStateOf(conv?.title.orEmpty()) }
    val focus = remember { FocusRequester() }
    val style = TextStyle(color = J.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    if (conv == null) {
        Text("New chat", style = style, maxLines = 1)
        return
    }
    if (!editing) {
        Hint("Click to rename") {
            Text(conv.title, style = style, maxLines = 1, modifier = Modifier.clicky { draft = conv.title; editing = true })
        }
        return
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it.replace("\n", "") },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(J.Accent),
        modifier = Modifier.widthIn(min = 160.dp, max = 420.dp)
            .border(1.dp, J.Accent.copy(alpha = 0.5f), HudShapeSmall).padding(horizontal = 8.dp, vertical = 4.dp)
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused && editing) editing = false }
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.Enter -> { a.rename(conv.id, draft); editing = false; true }
                    e.key == Key.Escape -> { editing = false; true }
                    else -> false
                }
            },
    )
}

@Composable
private fun ChatHeader(a: DesktopAssistant) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReactorOrb(size = 64.dp, state = orbStateOf(a), labels = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            EditableTitle(a)
            Spacer(Modifier.height(3.dp))
            StatusLine(a)
        }
        WakeChip(a)
        Spacer(Modifier.width(10.dp))
        Hint(if (a.speakAllReplies) "JARVIS reads every reply aloud — click to stop" else "Spoken questions get spoken answers — click to read every reply aloud") {
            Row(
                Modifier.height(36.dp).clip(HudShapeSmall)
                    .background(if (a.speakAllReplies) J.Accent.copy(alpha = 0.16f) else Color.Transparent)
                    .border(1.dp, if (a.speakAllReplies) J.Accent else J.Border, HudShapeSmall)
                    .clicky { a.speakAllReplies = !a.speakAllReplies }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.VolumeUp, null, tint = J.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (a.speakAllReplies) "Speaking replies" else "Speak replies", color = J.Text, fontSize = 13.sp)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(J.Hairline))
}

@Composable
private fun Conversation(a: DesktopAssistant) {
    val list = rememberLazyListState()
    val count = a.turns.size + if (a.thinkingHere) 1 else 0
    LaunchedEffect(a.activeId, count) { if (count > 0) list.animateScrollToItem(count - 1) }
    val accent = J.Accent
    SelectionContainer {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            itemsIndexed(a.turns) { _, turn ->
                Box(Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 40.dp)) {
                    when (turn.role) {
                        ChatTurn.USER -> UserMessage(turn.content)
                        ActionCard.ROLE -> ActionCard.decode(turn.content)?.let { StepCard(it) { a.undoAction(turn.id) } }
                        else -> AssistantMessage(turn.content, accent)
                    }
                }
            }
            a.pendingApproval?.let { ap ->
                item {
                    Box(Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 40.dp)) {
                        ApprovalCard(ap.description, ap.note, onApprove = { a.resolveApproval(true) }, onCancel = { a.resolveApproval(false) })
                    }
                }
            }
            if (a.thinkingHere && a.pendingApproval == null) item {
                Box(Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 40.dp)) { Thinking() }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun UserMessage(content: String) {
    // What came with the message (files, a screenshot) shows as chips above the words.
    val (marks, text) = DesktopTurn.splitAttachments(content)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        marks.forEach { m ->
            val (icon, mark) = when {
                m.startsWith(DesktopTurn.SHOT_MARK) -> Icons.Outlined.Monitor to DesktopTurn.SHOT_MARK
                m.startsWith(DesktopTurn.ROUTINE_MARK) -> Icons.Outlined.Schedule to DesktopTurn.ROUTINE_MARK
                else -> Icons.Outlined.Description to DesktopTurn.DOC_MARK
            }
            FileChip(icon, m.drop(mark.length).trim(), if (mark == DesktopTurn.ROUTINE_MARK) "routine" else null)
        }
        if (text.isNotEmpty()) {
            val shape = HudShapeSmall
            Text(
                text,
                color = J.Text, fontSize = 15.sp, lineHeight = 23.sp,
                modifier = Modifier.widthIn(max = 560.dp).clip(shape)
                    .background(J.Accent.copy(alpha = 0.16f)).border(1.dp, J.Accent.copy(alpha = 0.38f), shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** A file (or screenshot) riding with a message; [onRemove] adds the ✕ while it's still in the composer. */
@Composable
private fun FileChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String?, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.height(34.dp).clip(HudShapeSmall).background(J.Card).border(1.dp, J.CardBorder, HudShapeSmall).padding(start = 10.dp, end = if (onRemove != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = J.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = J.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(max = 260.dp))
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, color = J.TextDim, fontSize = 12.sp, maxLines = 1)
        }
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Close, "Remove", tint = J.TextDim, modifier = Modifier.size(26.dp).clip(CircleShape).clicky(onRemove).padding(5.dp))
        }
    }
}

@Composable
private fun AssistantMessage(text: String, accent: Color) {
    val phoneOnly = text.endsWith(DesktopTurn.PHONE_ONLY_NOTE)
    val body = if (phoneOnly) text.removeSuffix(DesktopTurn.PHONE_ONLY_NOTE).trimEnd() else text
    Row(Modifier.fillMaxWidth()) {
        StillOrb(34.dp, modifier = Modifier.padding(top = 0.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Jarvis", Modifier.padding(top = 8.dp), color = accent)
            if (body.isNotBlank()) MarkdownBody(body, accent)
            if (phoneOnly) ActionCard(
                icon = { Icon(Icons.Outlined.PhoneAndroid, null, tint = accent, modifier = Modifier.size(20.dp)) },
                title = "Phone only, for now",
                detail = "Opening and controlling apps works from JARVIS on your phone. On the laptop it arrives with Tasks (Phase 3).",
            )
        }
    }
}

@Composable
private fun ActionCard(icon: @Composable () -> Unit, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().clip(HudShape).background(J.Card)
            .border(1.dp, J.CardBorder, HudShape).hudBrackets(J.Accent.copy(alpha = 0.7f), inset = 3.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(J.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = J.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = J.TextDim, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

private val bodyStyle = TextStyle(color = J.TextBody, fontSize = 15.sp, lineHeight = 24.sp)

@Composable
fun MarkdownBody(text: String, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in Markdown.parse(text)) {
            when (block) {
                is Markdown.Block.Paragraph -> Text(styled(block.spans, accent), style = bodyStyle)
                is Markdown.Block.Heading -> Text(
                    styled(block.spans, accent),
                    style = bodyStyle.copy(color = J.Text, fontWeight = FontWeight.SemiBold, fontSize = if (block.level <= 2) 18.sp else 16.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Markdown.Block.Bullet -> ListRow("•", block.depth, accent) { Text(styled(block.spans, accent), style = bodyStyle) }
                is Markdown.Block.Numbered -> ListRow("${block.number}.", 0, accent) { Text(styled(block.spans, accent), style = bodyStyle) }
                is Markdown.Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).heightIn(min = 22.dp).background(accent.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(12.dp))
                    Text(styled(block.spans, accent), style = bodyStyle.copy(color = J.TextMuted, fontStyle = FontStyle.Italic))
                }
                is Markdown.Block.Code -> Text(
                    block.text,
                    color = J.Text, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = J.Mono,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(J.Card)
                        .border(1.dp, J.Hairline, RoundedCornerShape(10.dp)).padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, depth: Int, accent: Color, content: @Composable () -> Unit) {
    Row(Modifier.padding(start = (depth * 18).dp)) {
        Text(marker, style = bodyStyle.copy(color = accent), modifier = Modifier.width(24.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun styled(spans: List<Markdown.Span>, accent: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = when {
            s.code -> SpanStyle(fontFamily = J.Mono, background = Color(0x1AFFFFFF), color = accent, fontSize = 14.sp)
            else -> SpanStyle(
                fontWeight = if (s.bold) FontWeight.SemiBold else null,
                color = if (s.bold) J.Text else Color.Unspecified,
                fontStyle = if (s.italic) FontStyle.Italic else null,
            )
        }
        withStyle(style) { append(s.text) }
    }
}

@Composable
private fun Thinking() {
    val t = rememberInfiniteTransition()
    val alpha by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StillOrb(34.dp)
        Spacer(Modifier.width(12.dp))
        Text("THINKING", color = J.Accent, fontSize = 12.sp, fontFamily = J.Display, letterSpacing = 3.sp, modifier = Modifier.alpha(alpha))
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            message, color = Color(0xFFFF8A8A), fontSize = 13.sp,
            modifier = Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Composer(a: DesktopAssistant, textState: MutableState<String>, focus: FocusRequester) {
    var text by textState
    LaunchedEffect(a.activeId) { focus.requestFocus() }
    fun send() { if (a.send(text)) text = "" }
    val hasAttachments = a.pendingDocs.isNotEmpty() || a.pendingShot != null
    val canSend = (text.isNotBlank() || hasAttachments) && !a.thinking && a.importing == 0

    Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 780.dp).fillMaxWidth().clip(HudShape).background(J.Card)
                .border(1.dp, J.Accent.copy(alpha = 0.40f), HudShape)
                .hudBrackets(J.Accent, inset = 3.dp)
                .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        ) {
            // What will go with the next message: files (read locally already) and a screenshot.
            if (hasAttachments || a.importing > 0) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    a.pendingShotPreview?.let { img ->
                        Box(Modifier.height(54.dp).clip(HudShapeSmall).border(1.dp, J.Accent.copy(alpha = 0.6f), HudShapeSmall)) {
                            Image(img, "Screenshot to send", modifier = Modifier.height(54.dp))
                            Icon(
                                Icons.Outlined.Close, "Discard screenshot", tint = J.Text,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                                    .background(Color(0xAA000000)).clicky { a.discardShot() }.padding(3.dp),
                            )
                        }
                    }
                    a.pendingDocs.forEach { d ->
                        // Real pages mean something (PDF); "parts" are an internal cut, not worth showing.
                        FileChip(Icons.Outlined.Description, d.name, if (d.unit == "page") "${d.pages} page${if (d.pages == 1) "" else "s"}" else null) { a.removePending(d.id) }
                    }
                    if (a.importing > 0) Text("Reading ${if (a.importing == 1) "file" else "${a.importing} files"}…", color = J.Accent, fontSize = 12.sp)
                }
            }
            Box {
                if (text.isEmpty()) Text(
                    when {
                        a.voice == DesktopAssistant.Voice.Listening -> "Listening… speak now — I'll stop when you pause"
                        a.voice == DesktopAssistant.Voice.Transcribing -> "Transcribing…"
                        a.pendingShot != null -> "Ask about your screen… (Enter sends it with the screenshot)"
                        a.pendingDocs.isNotEmpty() -> "Ask about ${if (a.pendingDocs.size == 1) a.pendingDocs[0].name else "these files"}, or press Enter for a summary"
                        else -> "Ask anything, or tell JARVIS what to do… (Ctrl+Space to talk)"
                    },
                    color = if (a.voice == DesktopAssistant.Voice.Listening) J.Accent else J.TextFaint, fontSize = 15.sp,
                )
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = J.Text, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(J.Accent),
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                            send(); true
                        } else false
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("Give JARVIS a file: PDF, Word or text (or drag it onto the window)") {
                    Icon(
                        Icons.Outlined.AttachFile, "Attach a file", tint = J.Accent,
                        modifier = Modifier.size(36.dp).clip(CircleShape).clicky { pickFiles()?.let { a.attach(it) } }.padding(8.dp),
                    )
                }
                Hint(if (a.capturing) "Taking a screenshot…" else "Ask about your screen: JARVIS steps aside, takes one screenshot, and shows it here before anything is sent") {
                    Icon(
                        Icons.Outlined.Monitor, "Ask about my screen", tint = if (a.capturing) J.TextFaint else J.Accent,
                        modifier = Modifier.size(36.dp).clip(CircleShape).clicky { a.captureForQuestion() }.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.height(30.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, J.Border, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Computer, null, tint = J.TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("This laptop", color = J.TextMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("Enter to send · Shift+Enter for a new line", color = J.TextFaint, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                MicButton(a)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (canSend) J.Accent else Color(0x22FFFFFF))
                        .then(if (canSend) Modifier.clicky { send() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ArrowUpward, "Send", tint = if (canSend) J.OnAccent else J.TextFaint, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

/**
 * The talk button. Idle: click to talk (or Ctrl+Space). Listening: a ring that swells
 * with the real mic level; click to finish early. Speaking: click to cut JARVIS off.
 */
@Composable
private fun MicButton(a: DesktopAssistant) {
    val listening = a.voice == DesktopAssistant.Voice.Listening
    val busy = a.voice == DesktopAssistant.Voice.Transcribing
    val tip = when (a.voice) {
        DesktopAssistant.Voice.Listening -> "Listening — click to finish"
        DesktopAssistant.Voice.Transcribing -> "Transcribing…"
        DesktopAssistant.Voice.Speaking -> "Click to interrupt JARVIS and talk"
        else -> "Talk to JARVIS (Ctrl+Space)"
    }
    Hint(tip) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (listening) {
                val grow = 1f + a.micLevel.coerceIn(0f, 0.3f) * 2.2f
                Box(
                    Modifier.size((38 * grow).dp.coerceAtMost(58.dp)).clip(CircleShape)
                        .background(J.Accent.copy(alpha = 0.22f)),
                )
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(if (listening) J.Accent else Color.Transparent)
                    .border(1.dp, J.Accent.copy(alpha = if (busy) 0.3f else 0.7f), CircleShape)
                    .then(if (busy) Modifier.alpha(0.5f) else Modifier.clicky { a.toggleMic() }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic, tip,
                    tint = if (listening) J.OnAccent else J.Accent, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The wake-word switch. Off by default — an always-open mic is the user's choice. When
 * on, the dot glows while the background listener holds the mic; detection is local,
 * only the command after "Jarvis" is ever sent anywhere.
 */
@Composable
fun WakeChip(a: DesktopAssistant) {
    val on = a.wakeWordOn
    val tip = when {
        !on -> "Turn on to start JARVIS by saying “Jarvis” (listening stays on this laptop)"
        a.wakeListening -> "Listening for “Jarvis” — click to turn off"
        else -> "Wake word on — paused while JARVIS is busy"
    }
    Hint(tip) {
        Row(
            Modifier.height(36.dp).clip(HudShapeSmall)
                .background(if (on) J.Accent.copy(alpha = 0.16f) else Color.Transparent)
                .border(1.dp, if (on) J.Accent else J.Border, HudShapeSmall)
                .clicky { a.wakeWordOn = !a.wakeWordOn }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (on && a.wakeListening) J.Green else if (on) J.Accent.copy(alpha = 0.5f) else J.TextFaint),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (on) "Say “Jarvis”" else "Wake word off", color = J.Text, fontSize = 13.sp)
        }
    }
}

/**
 * One agent step, as the user sees it: what JARVIS did (✓) or couldn't do (✕), with Undo
 * for anything undoable (AGENT_PLAN §4). Every card is also in the Activity log.
 */
@Composable
private fun StepCard(card: ActionCard, onUndo: () -> Unit) {
    Row(
        Modifier.padding(start = 46.dp).fillMaxWidth().clip(HudShapeSmall).background(J.Card)
            .border(1.dp, if (card.ok) J.CardBorder else Color(0x55FF4D4D), HudShapeSmall)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(if (card.ok && !card.undone) J.Green.copy(alpha = 0.2f) else Color(0x33FF4D4D)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (card.ok && !card.undone) "✓" else if (card.undone) "↺" else "✕", color = if (card.ok && !card.undone) J.Green else Color(0xFFFF8A8A), fontSize = 11.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(card.summary, color = if (card.undone) J.TextDim else J.TextBody, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (card.undo != null && !card.undone && card.ok) {
            Text(
                "UNDO", color = J.Accent, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
                modifier = Modifier.clip(HudShapeSmall).clicky(onUndo).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** An irreversible step waiting for the user — nothing happens until they click (Rule 6). */
@Composable
private fun ApprovalCard(description: String, note: String, onApprove: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.padding(start = 46.dp).fillMaxWidth().clip(HudShape).background(Color(0x14FF9F1C))
            .border(1.dp, Color(0x88FF9F1C), HudShape).padding(14.dp),
    ) {
        Text("NEEDS YOUR OK", color = Color(0xFFFFC266), fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        Text("$description?", color = J.Text, fontSize = 14.sp)
        Text(note, color = J.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "APPROVE", color = J.OnAccent, fontSize = 11.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
                modifier = Modifier.clip(HudShapeSmall).background(J.Accent).clicky(onApprove).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                "CANCEL", color = J.Text, fontSize = 11.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
                modifier = Modifier.clip(HudShapeSmall).border(1.dp, J.Border, HudShapeSmall).clicky(onCancel).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The Windows file picker, showing the kinds JARVIS can read. Null when cancelled. */
fun pickFiles(): List<java.io.File>? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Give JARVIS a file", java.awt.FileDialog.LOAD)
    d.isMultipleMode = true
    // Windows reads a ';'-separated pattern list here (FilenameFilter is ignored on Windows).
    d.file = com.jarvis.os.desktop.knowledge.DocText.KINDS.joinToString(";") { "*.$it" }
    d.isVisible = true
    return d.files?.toList()?.takeIf { it.isNotEmpty() }
}
