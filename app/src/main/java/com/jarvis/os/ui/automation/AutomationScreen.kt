package com.jarvis.os.ui.automation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jarvis.os.ui.components.ScreenHeader
import com.jarvis.os.ui.theme.JarvisTheme
import com.jarvis.os.ui.theme.LocalAccent
import com.jarvis.os.ui.theme.TextPrimary
import com.jarvis.os.ui.theme.TextSecondary

/**
 * Automation — designed, and deliberately not working yet.
 *
 * This was a centred icon over the words "COMING SOON", which is the loudest
 * possible signal that an app is somebody's side project. Every shipping product
 * has unreleased features; almost none of them advertise the gap that way.
 *
 * What real products do instead — and what this now does — is present the feature
 * as a **real screen you cannot use yet**: the actual capabilities, laid out as
 * the rows they will be, behind a badge that says plainly it is not ready. That
 * reads as a roadmap rather than as a hole, and it does the one thing the empty
 * page could not: tell the user what is coming, which is the only good reason to
 * show an unfinished feature at all.
 *
 * The translucency carries the message. Nothing here is greyed-but-tappable,
 * which is the trap — a dimmed row that answers a touch is worse than no row.
 * These are drawn at reduced opacity and carry no click handler whatsoever, so
 * the screen cannot mislead even by accident.
 */
@Composable
fun AutomationScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Automation",
            subtitle = "Hands-off routines: JARVIS drives the apps on your phone so you do not have to.",
        )

        StatusBadge()
        Spacer(Modifier.height(22.dp))

        Text(
            text = "WHAT IT WILL DO",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        // The rows are the whole point of the screen. At 45% they stay legible
        // and unmistakably inactive — enough to read, not enough to invite a tap.
        Column(Modifier.alpha(0.45f)) {
            CAPABILITIES.forEach { capability ->
                CapabilityRow(capability)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Arriving in a later update. Nothing here runs yet, and JARVIS will " +
                "always ask before acting on your behalf.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * A pill, rather than a sentence.
 *
 * It is the convention users already read without thinking — how every app marks
 * a beta, a preview or a locked tier. A paragraph saying the same thing is
 * skipped.
 */
@Composable
private fun StatusBadge() {
    val accent = LocalAccent.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "IN DEVELOPMENT",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}

private class Capability(
    val icon: ImageVector,
    val title: String,
    val line: String,
)

private val CAPABILITIES = listOf(
    Capability(
        Icons.Filled.TouchApp,
        "Drive any app",
        "Open an app, find the right control and tap it — ordering, booking, replying.",
    ),
    Capability(
        Icons.Filled.Schedule,
        "Run on a schedule",
        "A routine at a time you choose, without being asked each morning.",
    ),
    Capability(
        Icons.Filled.Keyboard,
        "Type and send",
        "Compose a message, and confirm before anything leaves the phone.",
    ),
    Capability(
        Icons.Filled.Tune,
        "Chain steps together",
        "Several actions as one instruction, stopping the moment something looks wrong.",
    ),
)

@Composable
private fun CapabilityRow(capability: Capability) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(JarvisTheme.glass)
            .border(BorderStroke(1.dp, JarvisTheme.glassBorder), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LocalAccent.current.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = capability.icon,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(capability.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(capability.line, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
