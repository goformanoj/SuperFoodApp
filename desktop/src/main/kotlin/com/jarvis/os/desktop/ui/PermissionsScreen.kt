package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.desktop.DesktopAssistant

/**
 * What JARVIS is allowed to reach on this laptop, on its own — separate from Settings'
 * account/device switches, and given its own place in the sidebar because a safety
 * control deserves to be found, not buried at the bottom of a long settings page.
 *
 * Every category here defaults OFF: JARVIS starts with no reach at all, and the user
 * turns on exactly what they want. This never governs a file the user hands over
 * themselves (the paperclip, dragging it onto the window) — sharing something yourself
 * is always allowed; this page is only about JARVIS reaching for something on its own.
 */
@Composable
fun PermissionsScreen(a: DesktopAssistant) {
    Page(
        "Permissions",
        "What JARVIS may reach on its own. Off by default — turn on only what you want. A file you attach yourself always works, whatever these say: that's you sharing it, not JARVIS reaching for it.",
    ) {
        Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionRow(
                icon = Icons.Outlined.Description,
                title = "Laptop files",
                grants = "Search this laptop's files by name and content, open one it found, and read a document by typing its file path.",
                exempt = "Documents you attach yourself — the paperclip, or dragging a file onto JARVIS — always work, permission or not.",
                on = a.filesAllowed,
            ) { a.filesAllowed = it }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    grants: String,
    exempt: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(HudShape).background(J.Card).border(1.dp, J.CardBorder, HudShape)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = if (on) J.Accent else J.TextFaint, modifier = Modifier.padding(top = 2.dp).width(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = J.Text, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(grants, color = J.TextDim, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(4.dp))
            Text(exempt, color = J.TextFaint, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (on) "ON" else "OFF", color = if (on) J.OnAccent else J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp,
            modifier = Modifier.clip(HudShapeSmall).background(if (on) J.Accent else Color.Transparent)
                .border(1.dp, if (on) J.Accent else J.Border, HudShapeSmall)
                .clicky { onChange(!on) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
