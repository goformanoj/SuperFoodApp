package com.jarvis.os.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.theme.LocalAccent
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/**
 * The one header every screen uses.
 *
 * There were three of these, all slightly different. Settings pushed its title
 * 56dp from the left; Chat used a `Row` with a 44dp spacer; Calendar and Files
 * pushed 56dp *down* and nothing across. Same job, three implementations, three
 * results — and inconsistent margins between screens is one of the clearest
 * differences between an app that was designed and one that was assembled. Nobody
 * points at it, but it is felt on every navigation.
 *
 * The menu button is drawn by the host over the top-left corner, so the title has
 * to clear it. That fact belongs in ONE place, which is here.
 *
 * @param action optional trailing control, vertically centred against the title.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(HEADER_TOP))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Clears the ☰ the host overlays on the corner. Without it the icon
            // lands on the first letter and reads as a rendering fault.
            Spacer(Modifier.width(MENU_GUTTER))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            action()
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = MENU_GUTTER, top = 4.dp, end = 8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** How far down a screen's content starts, clearing the status bar and the ☰. */
private val HEADER_TOP = 56.dp

/** How far in a title must sit to clear the menu button the host draws. */
private val MENU_GUTTER = 44.dp

/**
 * What a screen shows when it has nothing to show.
 *
 * Every empty state in the app was a centred grey sentence. That is not wrong so
 * much as unfinished: an empty screen is the first thing a new user sees on most
 * of these, and a line of grey text tells them the app is broken rather than that
 * they have not started yet. An icon, a short line and a quiet instruction reads
 * as designed-for rather than not-got-to.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    line: String,
    modifier: Modifier = Modifier,
) {
    // `fillMaxWidth`, NOT `fillMaxSize`. A scrolling column hands its children an
    // unbounded height, and `fillMaxSize` in that context fails at measure time —
    // Files is inside a `verticalScroll`, so the obvious default would have taken
    // out a screen. Callers with a bounded box (Chat) pass `fillMaxSize`
    // themselves; the padding is what centres it anywhere else.
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // Dim: this is scenery, not a control, and an empty state whose
                // icon is the brightest thing on screen invites a tap that does
                // nothing.
                tint = LocalAccent.current.copy(alpha = 0.35f),
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
