package com.jarvis.os.desktop.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The HUD's panel shape: two opposite corners cut, like an instrument bezel. */
val HudShape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
val HudShapeSmall = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)

/** L-shaped brackets on all four corners, drawn over the content. */
fun Modifier.hudBrackets(color: Color, length: Dp = 12.dp, stroke: Dp = 1.5.dp, inset: Dp = 0.dp): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx(); val s = stroke.toPx(); val i = inset.toPx()
    val w = size.width; val h = size.height
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(color, Offset(x, y), Offset(x + dx * l, y), s, StrokeCap.Square)
        drawLine(color, Offset(x, y), Offset(x, y + dy * l), s, StrokeCap.Square)
    }
    corner(i, i, 1f, 1f)
    corner(w - i, i, -1f, 1f)
    corner(i, h - i, 1f, -1f)
    corner(w - i, h - i, -1f, -1f)
}

/**
 * A HUD panel: cut-corner glass, a hairline in the theme colour, bracket corners, and
 * a title bar in the display face. [code] is a quiet right-aligned tag (e.g. "01").
 */
@Composable
fun HudPanel(title: String, modifier: Modifier = Modifier, code: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(HudShape)
            .background(J.Glass)
            .border(1.dp, J.Accent.copy(alpha = 0.28f), HudShape)
            .hudBrackets(J.Accent.copy(alpha = 0.75f), inset = 3.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(J.Accent))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), color = J.Text, fontSize = 10.5.sp, fontFamily = J.Display, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            if (code != null) Text(code, color = J.Accent.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = J.Mono)
        }
        Spacer(Modifier.height(6.dp))
        // A scale line under the title: a long rule with a short bright lead.
        Box(Modifier.fillMaxWidth().height(1.dp).background(J.Accent.copy(alpha = 0.18f))) {
            Box(Modifier.width(34.dp).height(1.dp).background(J.Accent.copy(alpha = 0.9f)))
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** A label / value row in instrument style. */
@Composable
fun Readout(label: String, value: String, valueColor: Color = J.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = J.Mono)
    }
}

/** A 270° arc gauge with the value in the middle. */
@Composable
fun RingGauge(fraction: Float, value: String, label: String, size: Dp = 84.dp, color: Color = J.Accent) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 5.dp.toPx()
            val inset = s / 2 + 2.dp.toPx()
            val tl = Offset(inset, inset)
            val sz = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(color.copy(alpha = 0.14f), 135f, 270f, false, tl, sz, style = Stroke(s, cap = StrokeCap.Butt))
            // Tick marks on the track.
            for (i in 0..9) {
                drawArc(color.copy(alpha = 0.35f), 135f + i * 30f - 0.6f, 1.2f, false, tl, sz, style = Stroke(s * 1.8f))
            }
            drawArc(color.copy(alpha = 0.25f), 135f, 270f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(s * 2.6f, cap = StrokeCap.Butt))
            drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(s, cap = StrokeCap.Butt))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = J.Text, fontSize = 15.sp, fontFamily = J.Display)
            Text(label.uppercase(), color = J.TextDim, fontSize = 8.sp, fontFamily = J.Display, letterSpacing = 1.sp)
        }
    }
}

/** A filled sparkline of 0..1 values, newest on the right, with a baseline grid. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, color: Color = J.Accent, capacity: Int = 60) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (k in 1..3) drawLine(color.copy(alpha = 0.08f), Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
        if (values.size < 2) return@Canvas
        val step = w / (capacity - 1).coerceAtLeast(1)
        val startX = w - step * (values.size - 1)
        val line = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = h - v.coerceIn(0f, 1f) * h
            if (i == 0) { line.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) } else { line.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(1.5.dp.toPx()))
        val last = values.last()
        drawCircle(Color.White, 2.5.dp.toPx(), Offset(w, h - last.coerceIn(0f, 1f) * h))
    }
}

/** A thin horizontal meter. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = J.Accent) {
    Box(modifier.height(6.dp).clip(HudShapeSmall).background(color.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color))
    }
}

/** The faint instrument grid laid over the world, with a vignette to seat the content. */
@Composable
fun HudGrid(modifier: Modifier = Modifier, color: Color = J.Accent) {
    Canvas(modifier) {
        val step = 44.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(color.copy(alpha = 0.035f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(color.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                center = Offset(size.width / 2, size.height / 2),
                radius = maxOf(size.width, size.height) * 0.75f,
            ),
        )
    }
}

/** A HUD section divider: a rule with a bright lead and a small label. */
@Composable
fun HudRule(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(18.dp).height(1.dp).background(J.Accent))
        Text(label.uppercase(), color = J.TextDim, fontSize = 9.5.sp, fontFamily = J.Display, letterSpacing = 1.8.sp)
        Box(Modifier.weight(1f).height(1.dp).background(J.Accent.copy(alpha = 0.18f)))
    }
}
