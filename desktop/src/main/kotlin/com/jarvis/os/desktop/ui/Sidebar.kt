package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.GoogleSignIn
import com.jarvis.os.ui.components.JarvisWordmark

enum class Screen(val label: String, val icon: ImageVector, val primary: Boolean = true) {
    Chat("Chat", Icons.Outlined.ChatBubbleOutline),
    Tasks("Tasks", Icons.Outlined.TaskAlt),
    Scheduled("Scheduled", Icons.Outlined.Schedule),
    Memory("Memory", Icons.Outlined.AutoAwesome),
    Files("Files", Icons.Outlined.Description),
    Automations("Automations", Icons.Outlined.Bolt),
    // Secondary: a compact icon row at the foot of the sidebar, so the chat list keeps its room.
    Activity("Activity", Icons.Outlined.History, primary = false),
    Appearance("Themes", Icons.Outlined.Palette, primary = false),
    Settings("Settings", Icons.Outlined.Tune, primary = false),
}

@Composable
fun Sidebar(a: DesktopAssistant, screen: Screen, onHome: () -> Unit, onSearch: () -> Unit, onScreen: (Screen) -> Unit) {
    Column(Modifier.width(264.dp).fillMaxHeight().background(J.Glass)) {
        // The wordmark is the way home, as a logo is on any desktop app.
        Box(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
            Hint("Home") { JarvisWordmark(modifier = Modifier.clicky(onClick = onHome).padding(horizontal = 8.dp), scale = 0.62f, showSubtitle = true) }
        }

        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(44.dp)
                .clip(HudShape)
                .background(J.Accent.copy(alpha = 0.12f))
                .border(1.dp, J.Accent.copy(alpha = 0.45f), HudShape)
                .clicky { a.newChat(); onScreen(Screen.Chat) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, tint = J.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("New chat", color = J.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Ctrl N", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(36.dp).clip(HudShapeSmall)
                .border(1.dp, J.Border, HudShapeSmall).clicky(onSearch).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = J.TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text("Search everything", color = J.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("Ctrl K", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
        }
        Spacer(Modifier.height(12.dp))

        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Screen.entries.filter { it.primary }.forEach { s ->
                val badge = when (s) {
                    Screen.Memory -> a.memories.size.takeIf { it > 0 }?.toString()
                    Screen.Tasks -> a.openTasks.size.takeIf { it > 0 }?.toString()
                    else -> null
                }
                NavItem(s, selected = s == screen, badge = badge) { onScreen(s) }
            }
        }

        ChatList(a, screen, onOpen = { id -> a.select(id); onScreen(Screen.Chat) }, modifier = Modifier.weight(1f).padding(top = 6.dp))

        AccountFooter(a, screen, onScreen)
    }
}

@Composable
private fun NavItem(s: Screen, selected: Boolean, badge: String?, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(36.dp).clip(HudShapeSmall)
            .background(
                when {
                    selected -> J.Accent.copy(alpha = 0.14f)
                    hovered -> Color(0x0FFFFFFF)
                    else -> Color.Transparent
                },
            )
            .then(if (selected) Modifier.border(1.dp, J.Accent.copy(alpha = 0.30f), HudShapeSmall) else Modifier)
            .hoverable(hover)
            .clicky(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.icon, null, tint = if (selected) J.Accent else J.TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(s.label, color = if (selected) J.Text else J.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (badge != null) Pill(badge, fg = J.Accent, bg = J.Accent.copy(alpha = 0.14f), mono = true)
    }
}


@Composable
private fun AccountFooter(a: DesktopAssistant, screen: Screen, onScreen: (Screen) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Screen.entries.filter { !it.primary }.forEach { s ->
                val on = s == screen
                Hint(s.label) {
                    Box(
                        Modifier.size(34.dp).clip(HudShapeSmall)
                            .background(if (on) J.Accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(1.dp, if (on) J.Accent.copy(alpha = 0.5f) else J.Border, HudShapeSmall)
                            .clicky { onScreen(s) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(s.icon, s.label, tint = if (on) J.Accent else J.TextMuted, modifier = Modifier.size(17.dp)) }
                }
            }
            Spacer(Modifier.weight(1f))
            Pill("This laptop", fg = Color(0xFF7FF0C8), bg = J.Green.copy(alpha = 0.12f), dot = J.Green)
        }
        Row(
            Modifier.fillMaxWidth().clip(HudShape).background(J.Card)
                .border(1.dp, J.CardBorder, HudShape).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(J.Accent.copy(alpha = 0.12f)).border(1.dp, J.Accent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(a.account.initial.toString(), color = J.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${a.account.label()} · ${a.plan.replaceFirstChar { it.uppercase() }}",
                    color = J.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val u = a.usage
                if (u != null) {
                    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1AFFFFFF))) {
                        Box(
                            Modifier.fillMaxWidth(u.remaining.toFloat() / u.cap.coerceAtLeast(1)).height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(J.Accent),
                        )
                    }
                    Text("${UsageStats.format(u.remaining)} tokens left today", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
                } else {
                    Text("Usage shows after a reply", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
                }
            }
        }
        // Google sign-in: shown only once this build has a Desktop OAuth client.
        if (!a.account.isSignedIn && GoogleSignIn.isConfigured()) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).clip(HudShapeSmall)
                    .border(1.dp, J.Accent.copy(alpha = 0.45f), HudShapeSmall)
                    .then(if (a.signingIn) Modifier else Modifier.clicky { a.signInWithGoogle() })
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (a.signingIn) "Finish signing in in your browser…" else "Sign in with Google",
                    color = if (a.signingIn) J.TextDim else J.Text, fontSize = 13.sp,
                )
            }
        }
        a.signInError?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp) }
    }
}
