package com.jarvis.os.desktop.ui.stark

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jarvis.os.desktop.DesktopWorld
import com.jarvis.os.ui.theme.JarvisPalette

/** Draws [world] in [palette]'s colours. The one place that maps a world to its drawing. */
@Composable
fun DesktopWorldView(world: DesktopWorld, palette: JarvisPalette, modifier: Modifier = Modifier, live: Boolean = true, thumbnail: Boolean = false) {
    when (world) {
        DesktopWorld.Deck -> StarkWorld(palette, modifier, live, thumbnail)
        DesktopWorld.ReactorHall -> ReactorWorld(palette, modifier, live, thumbnail)
        DesktopWorld.Foundry -> FoundryWorld(palette, modifier, live, thumbnail)
        DesktopWorld.DeepSpace -> DeepSpaceWorld(palette, modifier, live, thumbnail)
        DesktopWorld.Orbital -> OrbitalWorld(palette, modifier, live, thumbnail)
    }
}
