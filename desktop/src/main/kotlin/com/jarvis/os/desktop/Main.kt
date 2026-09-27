package com.jarvis.os.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jarvis.os.data.ChatTurn

// The phone's palette (app/.../ui/theme/Color.kt), so the two feel like one product.
private val Background = Color(0xFF050B18)
private val Surface = Color(0xFF0A1426)
private val SurfaceGlass = Color(0x14FFFFFF)
private val GlassBorder = Color(0x1FFFFFFF)
private val Cyan = Color(0xFF00D4FF)
private val ElectricBlue = Color(0xFF0066FF)
private val TextPrimary = Color(0xFFE6F1FF)
private val TextSecondary = Color(0xFF8A97AB)
private val WarningOrange = Color(0xFFFF9F1C)
private val ErrorRed = Color(0xFFFF4D4D)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "JARVIS",
        state = rememberWindowState(size = DpSize(980.dp, 720.dp)),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Cyan,
                secondary = ElectricBlue,
                background = Background,
                surface = Surface,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
                error = ErrorRed,
            ),
        ) {
            val scope = rememberCoroutineScope()
            val assistant = remember { DesktopAssistant(scope) }
            JarvisScreen(assistant)
        }
    }
}

@Composable
private fun JarvisScreen(a: DesktopAssistant) {
    var showMemory by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Background, Color(0xFF071226), Background)),
        ),
    ) {
        Header(a, onMemory = { showMemory = true })
        if (!a.configured) SetupBanner()
        Messages(a, Modifier.weight(1f))
        a.error?.let { ErrorLine(it) }
        InputBar(a)
    }
    if (showMemory) MemoryDialog(a) { showMemory = false }
}

@Composable
private fun Header(a: DesktopAssistant, onMemory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Orb(active = a.thinking)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text("JARVIS", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 4.sp)
            Text(a.statusLine, color = TextSecondary, fontSize = 12.sp)
        }
        a.usageLine?.let { Text(it, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp)) }
        HeaderButton("What JARVIS remembers", onMemory) {
            Icon(Icons.Outlined.Psychology, contentDescription = "Memory", tint = Cyan)
        }
        HeaderButton("Clear the conversation", { a.clearConversation() }) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear", tint = TextSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderButton(tip: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { icon() }
    }
}

/** A small glowing orb — the phone's signature, reduced to a status light. */
@Composable
private fun Orb(active: Boolean) {
    val pulse = rememberInfiniteTransition()
    val glow by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (active) 500 else 1800), RepeatMode.Reverse),
    )
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(
            Brush.radialGradient(listOf(Cyan.copy(alpha = glow), ElectricBlue.copy(alpha = 0.35f * glow), Color.Transparent)),
        ),
    )
}

@Composable
private fun SetupBanner() {
    Box(
        Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(WarningOrange.copy(alpha = 0.10f)).border(1.dp, WarningOrange.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            "Not connected to the JARVIS server. Put PROXY_SECRET and FIREBASE_WEB_API_KEY in " +
                "%USERPROFILE%\\.gradle\\gradle.properties, then rebuild (gradlew :desktop:run).",
            color = WarningOrange, fontSize = 13.sp,
        )
    }
}

@Composable
private fun Messages(a: DesktopAssistant, modifier: Modifier) {
    val list = rememberLazyListState()
    val count = a.turns.size + if (a.thinking) 1 else 0
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
    if (a.turns.isEmpty() && !a.thinking) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("How can I help?", color = TextSecondary, fontSize = 18.sp)
        }
        return
    }
    SelectionContainer(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(a.turns) { Bubble(it) }
            if (a.thinking) item { Thinking() }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val mine = turn.role == ChatTurn.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp,
        )
        Box(
            Modifier.widthIn(max = 640.dp).clip(shape)
                .background(if (mine) ElectricBlue.copy(alpha = 0.28f) else SurfaceGlass)
                .border(1.dp, if (mine) ElectricBlue.copy(alpha = 0.5f) else GlassBorder, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(turn.content, color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun Thinking() {
    val pulse = rememberInfiniteTransition()
    val alpha by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    Text("JARVIS is thinking…", color = Cyan, fontSize = 13.sp, modifier = Modifier.alpha(alpha).padding(start = 4.dp))
}

@Composable
private fun ErrorLine(message: String) {
    Text(message, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
}

@Composable
private fun InputBar(a: DesktopAssistant) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun send() {
        if (a.send(text)) text = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message JARVIS  —  Enter to send, Shift+Enter for a new line", color = TextSecondary) },
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                    send(); true
                } else false
            },
            maxLines = 6,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = GlassBorder,
                focusedContainerColor = SurfaceGlass,
                unfocusedContainerColor = SurfaceGlass,
                cursorColor = Cyan,
            ),
        )
        IconButton(onClick = { send() }, enabled = text.isNotBlank() && !a.thinking) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (text.isNotBlank()) Cyan else TextSecondary)
        }
    }
}

@Composable
private fun MemoryDialog(a: DesktopAssistant, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("What JARVIS remembers", color = TextPrimary) },
        text = {
            if (a.facts.isEmpty()) {
                Text("Nothing yet. Tell JARVIS \"remember that …\" and it will keep it.", color = TextSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.facts.forEach { Text("•  $it", color = TextPrimary, fontSize = 14.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (a.facts.isNotEmpty()) TextButton(onClick = { a.forgetEverything() }) { Text("Forget all", color = ErrorRed) }
        },
    )
}
