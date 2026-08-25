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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
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
 * Automation — JARVIS reaching devices that are not this phone.
 *
 * **The first version of this screen described the wrong feature.** It talked
 * about driving apps on the handset — tapping buttons, filling forms — which is
 * what `ScreenControlService` already does on Home and is not what Automation is
 * for. Automation is *cross-device*: you speak to the phone in your hand and
 * something happens on a machine across the room. "Run the build on my desktop."
 * "Put the living room screen to sleep."
 *
 * That distinction is the whole feature, and getting it wrong made the screen
 * advertise a capability the app already had.
 *
 * Before that it was a centred icon over the words "COMING SOON", which is the
 * loudest possible signal that an app is somebody's side project. Every shipping
 * product has unreleased features; almost none advertise the gap that way.
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
            // Or the last row sits under the gesture bar.
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(
            title = "Automation",
            subtitle = "Speak to your phone, and it happens on another machine. " +
                "Your desktop, your laptop, anything you have paired.",
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
            text = "Arriving in a later update. No device can be paired yet, and " +
                "nothing here can reach a machine you have not signed in yourself.",
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
        Icons.Filled.DesktopWindows,
        "Run it on your desktop",
        "\"Start the build on my PC.\" The command leaves this phone and executes there.",
    ),
    Capability(
        Icons.Filled.Devices,
        "Pair once, reach anywhere",
        "Sign a device in from JARVIS and it stays available, on the same network or off it.",
    ),
    Capability(
        Icons.Filled.PlayArrow,
        "Your own commands",
        "Name a script or a shortcut, and JARVIS learns to run it by the name you gave it.",
    ),
    Capability(
        Icons.Filled.Schedule,
        "On a schedule",
        "The same routine at a time you choose, on whichever machine should be doing it.",
    ),
    Capability(
        Icons.Filled.VerifiedUser,
        "Approved, every time",
        "Nothing runs on another machine without a confirmation naming the device and the command.",
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
