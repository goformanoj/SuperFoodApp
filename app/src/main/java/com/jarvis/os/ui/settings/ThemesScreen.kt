package com.jarvis.os.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.OrbPreview
import com.jarvis.os.ui.components.ThemeBackdrop
import com.jarvis.os.ui.theme.BackdropStyle
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.JarvisTheme
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
    /** The stored backdrop id, or empty for "whatever this theme comes with". */
    backdropId: String = "",
    onSelectBackdrop: (String) -> Unit = {},
) {
    // Lazy, not a scrolling Column: every card runs a live 3D orb, and a Column
    // composes and animates all six whether or not they are on screen. Only the
    // visible ones should be costing frames.
    // A GRID, because the backgrounds are pictures.
    //
    // Ten scenes as full-width rows is ten screens of scrolling to compare two
    // of them, and these differ by STRUCTURE — you have to see them side by side
    // or the choice is meaningless. Three across shows nine at once.
    //
    // Themes stay full-width (they span every column): a theme card carries a
    // live orb and a sentence of description, which does not survive being cut
    // to a third of the width.
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.systemBarsPadding())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // A COLUMN, and every header on this screen needed one.
            //
            // A lazy item's content lambda is not a layout — it is a slot that
            // takes ONE child. Put three composables in it and all three are
            // measured at the same origin, so they draw on top of each other. A
            // screenshot caught "Themes" printed straight through its own
            // description, and the same fault was in all three headers here.
            Column {
                if (!embedded) Spacer(Modifier.height(56.dp))
                Text("Themes", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text(
                    "Tap one to switch. Each preview is live — it is the same orb you get.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
            }
        }

        items(JarvisPalette.entries, key = { "theme:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { palette ->
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

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(28.dp))
                Text("Background", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    "Every theme arrives with its own. Pick another and it stays until you " +
                        "change themes again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
            }
        }

        // "Match theme" is its own row rather than a checkbox, because it is a
        // different KIND of answer from the ten below it: those name a scene,
        // this one declines to. Stored as an empty id so it keeps following the
        // theme after a theme change — storing the resolved backdrop instead
        // would silently freeze whichever one happened to be showing.
        item(key = "backdrop:match") {
            val following = BackdropStyle.entries.none { it.id == backdropId }
            BackdropTile(
                title = "Match theme",
                palette = current,
                backdrop = BackdropStyle.defaultFor(current.orbStyle),
                selected = following,
                onClick = { onSelectBackdrop("") },
            )
        }

        items(BackdropStyle.entries, key = { "backdrop:${it.id}" }) { style ->
            BackdropTile(
                title = style.displayName,
                palette = current,
                backdrop = style,
                selected = style.id == backdropId,
                onClick = { onSelectBackdrop(style.id) },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Spacer(Modifier.height(16.dp))
                Text(
                    "A theme changes the orb's shape and motion, the accent colours and the " +
                        "backdrop it starts with. The background can then be changed on its own. " +
                        "Both choices are remembered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/**
 * One background, as a tile: the scene above, its name below.
 *
 * A picture, because these ten differ by STRUCTURE rather than by hue — a canyon
 * and a dune under the same theme share a palette entirely, so a colour swatch
 * would have shown ten identical squares. The thumbnail is the real backdrop
 * renderer in the real theme colours, so what you pick is what you get.
 *
 * It is drawn STILL. Eleven live backdrops on one screen is eleven infinite
 * transitions each invalidating a Canvas at 60fps — the same fault the theme
 * picker already had with seven live orbs, and it lagged Settings the same way.
 */
@Composable
private fun BackdropTile(
    title: String,
    palette: JarvisPalette,
    backdrop: BackdropStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border by animateColorAsState(
        if (selected) palette.accent else JarvisTheme.glassBorder,
        tween(300),
        label = "backdropBorder",
    )
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Portrait, like the phone it represents. A landscape chip of a
                // full-screen scene crops away the thing that identifies it —
                // the horizon, the ridgeline, the curtain standing on the ground.
                .aspectRatio(0.62f)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.background)
                .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(14.dp)),
        ) {
            ThemeBackdrop(palette = palette, backdrop = backdrop, thumbnail = true)
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) palette.accent else TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )
    }
}
