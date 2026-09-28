package com.jarvis.os.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.DesktopTurn
import com.jarvis.os.desktop.Markdown

@Composable
fun ChatScreen(a: DesktopAssistant, focus: FocusRequester) {
    Column(Modifier.fillMaxSize()) {
        ChatHeader(a)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (a.turns.isEmpty() && !a.thinkingHere) EmptyChat(a) else Conversation(a)
        }
        a.error?.let { ErrorBanner(it) }
        Composer(a, focus)
    }
}

@Composable
private fun ChatHeader(a: DesktopAssistant) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.active?.title ?: "New chat", color = J.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Pill("Runs on this laptop")
        Spacer(Modifier.weight(1f))
        Hint("Voice mode arrives in Phase 2 of the roadmap") {
            Row(
                Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, J.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp).alpha(0.55f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Mic, null, tint = J.Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Voice mode", color = J.Text, fontSize = 13.sp)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(J.Hairline))
}

@Composable
private fun EmptyChat(a: DesktopAssistant) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Orb(64.dp, active = a.thinking)
        Spacer(Modifier.height(24.dp))
        Text("How can I help?", color = J.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (a.configured) "Ask anything. Tell me what to remember and I'll keep it."
            else "Not connected to the JARVIS server yet — open Settings.",
            color = J.TextDim, fontSize = 15.sp,
        )
    }
}

@Composable
private fun Conversation(a: DesktopAssistant) {
    val list = rememberLazyListState()
    val count = a.turns.size + if (a.thinkingHere) 1 else 0
    LaunchedEffect(a.activeId, count) { if (count > 0) list.animateScrollToItem(count - 1) }
    SelectionContainer {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(a.turns) { _, turn ->
                Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 40.dp)) {
                    if (turn.role == ChatTurn.USER) UserMessage(turn.content) else AssistantMessage(turn.content)
                }
            }
            if (a.thinkingHere) item {
                Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 40.dp)) { Thinking() }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
        Text(
            text,
            color = J.Text, fontSize = 15.sp, lineHeight = 23.sp,
            modifier = Modifier.widthIn(max = 560.dp).clip(shape)
                .background(J.Blue.copy(alpha = 0.16f)).border(1.dp, J.Blue.copy(alpha = 0.35f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AssistantMessage(text: String) {
    val phoneOnly = text.endsWith(DesktopTurn.PHONE_ONLY_NOTE)
    val body = if (phoneOnly) text.removeSuffix(DesktopTurn.PHONE_ONLY_NOTE).trimEnd() else text
    Row(Modifier.fillMaxWidth()) {
        Orb(26.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("JARVIS", color = J.CyanSoft, fontSize = 11.sp, fontFamily = J.Mono, letterSpacing = 1.sp)
            if (body.isNotBlank()) MarkdownBody(body)
            if (phoneOnly) ActionCard(
                icon = { Icon(Icons.Outlined.PhoneAndroid, null, tint = J.Cyan, modifier = Modifier.size(20.dp)) },
                title = "Phone only, for now",
                detail = "Opening and controlling apps works from JARVIS on your phone. On the laptop it arrives with Tasks (Phase 3).",
            )
        }
    }
}

@Composable
private fun ActionCard(icon: @Composable () -> Unit, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(J.Surface)
            .border(1.dp, J.Border, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(J.Cyan.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = J.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = J.TextDim, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

private val bodyStyle = TextStyle(color = J.TextBody, fontSize = 15.sp, lineHeight = 24.sp)

@Composable
fun MarkdownBody(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in Markdown.parse(text)) {
            when (block) {
                is Markdown.Block.Paragraph -> Text(styled(block.spans), style = bodyStyle)
                is Markdown.Block.Heading -> Text(
                    styled(block.spans),
                    style = bodyStyle.copy(color = J.Text, fontWeight = FontWeight.SemiBold, fontSize = if (block.level <= 2) 18.sp else 16.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Markdown.Block.Bullet -> ListRow(marker = "•", depth = block.depth) { Text(styled(block.spans), style = bodyStyle) }
                is Markdown.Block.Numbered -> ListRow(marker = "${block.number}.", depth = 0) { Text(styled(block.spans), style = bodyStyle) }
                is Markdown.Block.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).heightIn(min = 22.dp).background(J.Cyan.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(12.dp))
                    Text(styled(block.spans), style = bodyStyle.copy(color = J.TextMuted, fontStyle = FontStyle.Italic))
                }
                is Markdown.Block.Code -> Text(
                    block.text,
                    color = J.Text, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = J.Mono,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(J.Sidebar)
                        .border(1.dp, J.Hairline, RoundedCornerShape(10.dp)).padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, depth: Int, content: @Composable () -> Unit) {
    Row(Modifier.padding(start = (depth * 18).dp)) {
        Text(marker, style = bodyStyle.copy(color = J.CyanSoft), modifier = Modifier.width(24.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun styled(spans: List<Markdown.Span>): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = when {
            s.code -> SpanStyle(fontFamily = J.Mono, background = Color(0x14FFFFFF), color = J.CyanSoft, fontSize = 14.sp)
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
        Orb(26.dp, active = true)
        Spacer(Modifier.width(14.dp))
        Text("Thinking…", color = J.Cyan, fontSize = 14.sp, modifier = Modifier.alpha(alpha))
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            message, color = Color(0xFFFF8A8A), fontSize = 13.sp,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 40.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Composer(a: DesktopAssistant, focus: FocusRequester) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(a.activeId) { focus.requestFocus() }
    fun send() { if (a.send(text)) text = "" }
    val canSend = text.isNotBlank() && !a.thinking

    Box(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(J.Surface)
                .border(1.dp, J.Cyan.copy(alpha = 0.30f), RoundedCornerShape(18.dp))
                .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        ) {
            Box {
                if (text.isEmpty()) Text("Ask anything, or tell JARVIS what to remember…", color = J.TextFaint, fontSize = 15.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = J.Text, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(J.Cyan),
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
                        Modifier.size(38.dp).clip(CircleShape).border(1.dp, J.Cyan.copy(alpha = 0.25f), CircleShape).alpha(0.5f),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Mic, "Talk to JARVIS", tint = J.Cyan, modifier = Modifier.size(18.dp)) }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(if (canSend) J.Cyan else Color(0x22FFFFFF))
                        .then(if (canSend) Modifier.clickable { send() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ArrowUpward, "Send", tint = if (canSend) J.OnCyan else J.TextFaint, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}
