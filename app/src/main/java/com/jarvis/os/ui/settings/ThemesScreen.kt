package com.jarvis.os.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.GlassBorder
import com.jarvis.os.ui.theme.JarvisPalette
import com.jarvis.os.ui.theme.SurfaceGlass
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/**
 * Pick the app's look. The choice is real and persisted; only the accent moves
 * for now, with the full re-skin waiting on designs — so adding one later is a
 * data change, not a rewrite.
 */
@Composable
fun ThemesScreen(
    current: JarvisPalette,
    onSelect: (JarvisPalette) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        Text("Themes", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        Text(
            "How JARVIS looks. Your choice is remembered.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        JarvisPalette.entries.forEach { palette ->
            val selected = palette == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) palette.accent.copy(alpha = 0.10f) else SurfaceGlass)
                    .border(
                        1.dp,
                        if (selected) palette.accent else GlassBorder,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(palette) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(palette.accent, palette.secondary))),
                )
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        palette.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) palette.accent else TextPrimary,
                    )
                    Text(palette.blurb, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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

        Spacer(Modifier.height(20.dp))
        Text(
            "Right now a theme changes the accent colour throughout. Full layouts land " +
                "once the designs are decided.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(40.dp))
    }
}
