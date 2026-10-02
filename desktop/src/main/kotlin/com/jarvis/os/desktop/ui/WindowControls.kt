package com.jarvis.os.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.os.desktop.WindowChrome

/**
 * The window's own minimise / maximise / close — drawn here because the native Windows title bar
 * is gone (see [WindowChrome]). Sits in the top-right corner of the title strip. The strip's
 * draggable part is the OS's (a caption hit-test), not this: these three buttons are ordinary
 * clickable Compose, which is why [WindowChrome.CONTROLS_DP] is carved out of the caption area.
 */
@Composable
fun WindowControls(
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.height(WindowChrome.TITLE_BAR_DP.dp)) {
        ControlButton(onMinimize, danger = false) { c -> drawMinimize(c) }
        ControlButton(onToggleMaximize, danger = false) { c -> if (maximized) drawRestore(c) else drawMaximize(c) }
        ControlButton(onClose, danger = true) { c -> drawClose(c) }
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, danger: Boolean, glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        hovered && danger -> Color(0xFFE81123)
        hovered -> Color.White.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    val ink = if (hovered && danger) Color.White else J.TextMuted
    Box(
        Modifier.width(WindowChrome.BUTTON_DP.dp).fillMaxHeight().background(bg)
            .hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { glyph(ink) }
    }
}

private val thin = 1f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMinimize(c: Color) {
    drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = thin * density, cap = StrokeCap.Butt)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaximize(c: Color) {
    drawRect(c, topLeft = Offset(0.5f * density, 0.5f * density), size = Size(size.width - density, size.height - density), style = Stroke(thin * density))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRestore(c: Color) {
    val s = size.width
    val back = Size(s * 0.72f, s * 0.72f)
    // The window behind (only its top and right edges show) …
    drawLine(c, Offset(s * 0.28f, 0.5f * density), Offset(s - 0.5f * density, 0.5f * density), strokeWidth = thin * density)
    drawLine(c, Offset(s - 0.5f * density, 0.5f * density), Offset(s - 0.5f * density, s * 0.72f), strokeWidth = thin * density)
    // … and the one in front.
    drawRect(c, topLeft = Offset(0.5f * density, s * 0.28f + 0.5f * density), size = Size(back.width - density, back.height - density), style = Stroke(thin * density))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClose(c: Color) {
    drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = thin * density)
    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = thin * density)
}
