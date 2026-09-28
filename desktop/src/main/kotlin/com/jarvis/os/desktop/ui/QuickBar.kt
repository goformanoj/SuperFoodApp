package com.jarvis.os.desktop.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.DesktopPrefs
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest
import java.awt.Cursor
import com.jarvis.os.ui.theme.JarvisPalette
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * The Quick bar (AGENT_PLAN §6): Alt+Space anywhere → a small box over whatever the user is
 * doing. Ask, or act on what they copied ("rewrite this more politely"); the answer shows
 * right there with Copy. Enter asks, Esc closes; follow-ups continue the same chat, which
 * is also in the main window's history.
 */
@Composable
fun ApplicationScope.QuickBar(
    a: DesktopAssistant, palette: JarvisPalette, visible: Boolean,
    saved: DesktopPrefs.QuickGeometry, onSave: (DesktopPrefs.QuickGeometry) -> Unit,
    onClose: () -> Unit, onOpenMain: () -> Unit,
) {
    val screen = remember { Toolkit.getDefaultToolkit().screenSize }
    val state = rememberWindowState(
        size = DpSize(saved.width.dp, COLLAPSED),
        // AWT reports the screen in logical units (Java is DPI-aware on Windows), the same units as dp.
        position = if (saved.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp)
        else WindowPosition((screen.width / 2 - saved.width / 2).dp, (screen.height * 0.18f).dp),
    )
    // The user's size: width always, height for when an answer is showing.
    var expandedHeight by remember { mutableStateOf(saved.height) }
    val hasAnswer = a.quickAnswer != null || a.quickThinking || a.quickError != null
    LaunchedEffect(hasAnswer) { state.size = DpSize(state.size.width, if (hasAnswer) expandedHeight.dp else COLLAPSED) }
    // Remember where it was moved and how big it was made (saved half a second after it stops changing).
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.position, state.size.width, expandedHeight) }.collectLatest { (pos, w, h) ->
            kotlinx.coroutines.delay(500)
            onSave(DesktopPrefs.QuickGeometry(w.value, h, pos.x.value.takeIf { pos.isSpecified }, pos.y.value.takeIf { pos.isSpecified }))
        }
    }

    Window(
        onCloseRequest = onClose,
        visible = visible,
        title = "JARVIS Quick bar",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        state = state,
        onPreviewKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false },
    ) {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(visible) {
            if (visible) {
                window.toFront()
                window.requestFocus()
                runCatching { focus.requestFocus() }
            }
        }
        DesktopTheme(palette) {
          Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().clip(HudShape).background(palette.background.copy(alpha = 0.97f))
                    .border(1.dp, J.Accent.copy(alpha = 0.6f), HudShape).hudBrackets(J.Accent, inset = 3.dp)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The handle: drag the grip or the orb to move the bar.
                    Hint("Drag to move") {
                        WindowDraggableArea(Modifier.pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.DragIndicator, "Move", tint = J.TextFaint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                                StillOrb(30.dp)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text(
                            if (a.quickAnswer != null) "Follow up… (Esc to close)" else "Ask JARVIS — or “rewrite what I copied more politely”",
                            color = J.TextFaint, fontSize = 17.sp,
                        )
                        BasicTextField(
                            value = text, onValueChange = { text = it },
                            singleLine = true,
                            textStyle = TextStyle(color = J.Text, fontSize = 17.sp),
                            cursorBrush = SolidColor(J.Accent),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).onEnter {
                                if (text.isNotBlank() && !a.thinking && a.quickAsk(text)) text = ""
                            },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("ESC", color = J.TextFaint, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp)
                }
                if (hasAnswer) {
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(J.Hairline))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            a.quickThinking -> Text("THINKING…", color = J.Accent, fontSize = 12.sp, fontFamily = J.Display, letterSpacing = 3.sp)
                            a.quickError != null -> Text(a.quickError!!, color = Color(0xFFFF8A8A), fontSize = 14.sp)
                            else -> SelectionContainer {
                                Column(Modifier.verticalScroll(rememberScrollState())) { MarkdownBody(a.quickAnswer.orEmpty(), J.Accent) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val answer = a.quickAnswer
                        if (answer != null && !a.quickThinking) {
                            QuickButton("COPY", primary = true) {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(answer), null)
                            }
                        }
                        QuickButton("OPEN IN JARVIS") { onOpenMain() }
                        Spacer(Modifier.weight(1f))
                        Text("Enter to ask · Esc to close", color = J.TextFaint, fontSize = 11.sp)
                        Spacer(Modifier.width(18.dp))
                    }
                }
            }
            // The resize corner: width always; height too while an answer is showing.
            val density = LocalDensity.current
            Box(Modifier.align(Alignment.BottomEnd)) {
                Icon(
                    Icons.Outlined.OpenInFull, "Resize", tint = J.TextFaint,
                    modifier = Modifier.padding(4.dp).size(16.dp).graphicsLayer { rotationZ = 90f }
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
                        .pointerInput(hasAnswer) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = with(density) { drag.x.toDp() }
                                val dy = with(density) { drag.y.toDp() }
                                val w = (state.size.width + dx).coerceIn(DesktopPrefs.QuickGeometry.MIN_W.dp, DesktopPrefs.QuickGeometry.MAX_W.dp)
                                val h = if (hasAnswer) (state.size.height + dy).coerceIn(DesktopPrefs.QuickGeometry.MIN_H.dp, DesktopPrefs.QuickGeometry.MAX_H.dp) else COLLAPSED
                                if (hasAnswer) expandedHeight = h.value
                                state.size = DpSize(w, h)
                            }
                        },
                )
            }
          }
        }
    }
}

@Composable
private fun QuickButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (primary) J.OnAccent else J.Text, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
        modifier = Modifier.heightIn(min = 30.dp).clip(HudShapeSmall).background(if (primary) J.Accent else Color.Transparent)
            .border(1.dp, if (primary) J.Accent else J.Border, HudShapeSmall).clicky(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** The bar with no answer showing: just the input row. */
private val COLLAPSED = 74.dp

private fun Modifier.onEnter(action: () -> Unit): Modifier =
    onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { action(); true } else false }
