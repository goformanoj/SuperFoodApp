package com.jarvis.os.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.jarvis.os.voice.OrbState
import java.time.LocalTime

/** The orb's state is the app's REAL state — never decoration. */
private fun orbStateOf(a: DesktopAssistant): OrbState = when {
    !a.configured -> OrbState.Offline
    a.thinkingHere -> OrbState.Thinking
    a.error != null -> OrbState.Error
    else -> OrbState.Idle
}

private fun statusOf(a: DesktopAssistant): String = when {
    !a.configured -> "Not connected"
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
) {
    Column(Modifier.fillMaxSize()) {
        if (hasConversation(a)) {
            ChatHeader(a)
            Box(Modifier.weight(1f).fillMaxWidth()) { Conversation(a) }
        } else {
            Cockpit(a, telemetry, text, Modifier.weight(1f), onMemory)
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
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Plan my day", "Explain it simply", "Remember something").forEach { s -> Suggestion(s) { text.value = suggestionText(s) } }
                }
            }
            if (wide) Column(Modifier.width(236.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MemoryPanel(a, onMemory, Modifier.fillMaxWidth())
                SessionPanel(a, Modifier.fillMaxWidth())
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

@Composable
private fun ChatHeader(a: DesktopAssistant) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReactorOrb(size = 64.dp, state = orbStateOf(a), labels = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.active?.title ?: "New chat", color = J.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            StatusLine(a)
        }
        Pill("Runs on this laptop")
        Spacer(Modifier.width(10.dp))
        Hint("Voice mode arrives in Phase 2 of the roadmap") {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, J.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp).alpha(0.55f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Mic, null, tint = J.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Voice", color = J.Text, fontSize = 13.sp)
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
                    if (turn.role == ChatTurn.USER) UserMessage(turn.content) else AssistantMessage(turn.content, accent)
                }
            }
            if (a.thinkingHere) item {
                Box(Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 40.dp)) { Thinking() }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
    val canSend = text.isNotBlank() && !a.thinking

    Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 780.dp).fillMaxWidth().clip(HudShape).background(J.Card)
                .border(1.dp, J.Accent.copy(alpha = 0.40f), HudShape)
                .hudBrackets(J.Accent, inset = 3.dp)
                .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        ) {
            Box {
                if (text.isEmpty()) Text("Ask anything, or tell JARVIS what to remember…", color = J.TextFaint, fontSize = 15.sp)
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
                Hint("Attaching files arrives with Tasks (Phase 3)") {
                    Icon(Icons.Outlined.AttachFile, "Attach a file", tint = J.TextFaint, modifier = Modifier.size(36.dp).padding(8.dp))
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
                Hint("Talking to JARVIS arrives in Phase 2") {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).border(1.dp, J.Accent.copy(alpha = 0.3f), CircleShape).alpha(0.5f),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Mic, "Talk to JARVIS", tint = J.Accent, modifier = Modifier.size(18.dp)) }
                }
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
