package com.jarvis.os.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.OrbPreview
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/**
 * Pick the app's look — six designs, each with its own orb geometry, colours and
 * motion.
 *
 * Every card runs the real orb renderer rather than showing a colour swatch.
 * Three of these themes differ mainly in how they MOVE, so a still preview would
 * make them look like the same design in different colours, which is exactly the
 * choice the user needs to be able to make.
 */
@Composable
fun ThemesScreen(
    current: JarvisPalette,
    onSelect: (JarvisPalette) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = true,
) {
    // Lazy, not a scrolling Column: every card runs a live 3D orb, and a Column
    // composes and animates all six whether or not they are on screen. Only the
    // visible ones should be costing frames.
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.systemBarsPadding())
            .padding(horizontal = 20.dp),
    ) {
        item {
            if (!embedded) Spacer(Modifier.height(56.dp))
            Text("Themes", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Text(
                "Tap one to switch. Each preview is live — it is the same orb you get.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }

        items(JarvisPalette.entries, key = { it.id }) { palette ->
            val selected = palette == current
            val borderColor by animateColorAsState(
                if (selected) palette.accent else JarvisTheme.glassBorder,
                tween(300),
                label = "border",
            )
            val previewSize by animateDpAsState(
                if (selected) 104.dp else 88.dp,
                tween(300),
                label = "preview",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                palette.background,
                                palette.surface.copy(alpha = if (selected) 0.95f else 0.7f),
                            ),
                        ),
                    )
                    .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(18.dp))
                    .clickable { onSelect(palette) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(112.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Only the chosen card runs its clocks — seven live orbs is what made
                    // this screen lag. Tap a card and it comes to life.
                    OrbPreview(palette = palette, size = previewSize, animated = selected)
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        palette.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) palette.accent else TextPrimary,
                    )
                    Text(
                        palette.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (selected) {
                        Text(
                            "In use",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (selected) {
                    Spacer(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "A theme changes the orb's shape and motion, the accent colours and the " +
                    "backdrop. Your choice is remembered.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
