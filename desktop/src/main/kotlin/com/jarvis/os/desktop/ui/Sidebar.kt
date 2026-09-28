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

enum class Screen(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Outlined.ChatBubbleOutline),
    Tasks("Tasks", Icons.Outlined.TaskAlt),
    Scheduled("Scheduled", Icons.Outlined.Schedule),
    Memory("Memory", Icons.Outlined.AutoAwesome),
    Files("Files", Icons.Outlined.Description),
    Automations("Automations", Icons.Outlined.Bolt),
    Settings("Settings", Icons.Outlined.Tune),
}

@Composable
fun Sidebar(a: DesktopAssistant, screen: Screen, onScreen: (Screen) -> Unit) {
    Column(
        Modifier.width(264.dp).fillMaxHeight().background(J.Sidebar),
    ) {
        Row(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Orb(30.dp, active = a.thinking)
            Spacer(Modifier.width(12.dp))
            Text("JARVIS", color = J.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.5.sp)
        }

        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(J.Cyan.copy(alpha = 0.08f))
                .border(1.dp, J.Cyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .clickable { a.newChat(); onScreen(Screen.Chat) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, tint = J.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("New chat", color = J.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("Ctrl N", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
        }
        Spacer(Modifier.height(14.dp))

        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Screen.entries.forEach { s ->
                NavItem(s, selected = s == screen, badge = badgeFor(s, a)) { onScreen(s) }
            }
        }

        Eyebrow("Recent", Modifier.padding(start = 22.dp, top = 22.dp, bottom = 8.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (a.conversations.isEmpty()) {
                item { Text("Your chats will appear here.", color = J.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            }
            items(a.conversations, key = { it.id }) { c ->
                RecentItem(
                    title = c.title,
                    selected = screen == Screen.Chat && c.id == a.activeId,
                    busy = a.thinkingIn == c.id,
                    onOpen = { a.select(c.id); onScreen(Screen.Chat) },
                    onDelete = { a.delete(c.id) },
                )
            }
        }

        AccountFooter(a)
    }
}

private fun badgeFor(s: Screen, a: DesktopAssistant): String? = when (s) {
    Screen.Memory -> a.facts.size.takeIf { it > 0 }?.toString()
    else -> null
}

@Composable
private fun NavItem(s: Screen, selected: Boolean, badge: String?, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> J.Cyan.copy(alpha = 0.10f)
                    hovered -> Color(0x0AFFFFFF)
                    else -> Color.Transparent
                },
            )
            .then(if (selected) Modifier.border(1.dp, J.Cyan.copy(alpha = 0.22f), RoundedCornerShape(10.dp)) else Modifier)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.icon, null, tint = if (selected) J.Cyan else J.TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(s.label, color = if (selected) J.Text else J.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (badge != null) Pill(badge, fg = J.CyanSoft, bg = J.Cyan.copy(alpha = 0.12f), mono = true)
    }
}

@Composable
private fun RecentItem(title: String, selected: Boolean, busy: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x0FFFFFFF) else if (hovered) Color(0x08FFFFFF) else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(J.Cyan))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title, color = if (selected) J.Text else J.TextMuted, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (hovered && !busy) {
            Hint("Delete chat") {
                Icon(
                    Icons.Outlined.Close, "Delete chat", tint = J.TextDim,
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelete).padding(5.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountFooter(a: DesktopAssistant) {
    Column(
        Modifier.fillMaxWidth().background(J.Sidebar).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("This laptop", fg = Color(0xFF7FF0C8), bg = J.Green.copy(alpha = 0.10f), dot = J.Green)
            Hint("Linking your phone arrives in Phase 5") { Pill("Phone", dot = J.TextFaint) }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x0AFFFFFF))
                .border(1.dp, J.Hairline, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(J.Raised).border(1.dp, J.Cyan.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("G", color = J.CyanSoft, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Guest · ${a.plan.replaceFirstChar { it.uppercase() }}", color = J.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val u = a.usage
                if (u != null) {
                    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x14FFFFFF))) {
                        Box(
                            Modifier.fillMaxWidth(u.remaining.toFloat() / u.cap.coerceAtLeast(1)).height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(J.Cyan),
                        )
                    }
                    Text("${UsageStats.format(u.remaining)} tokens left today", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
                } else {
                    Text("Usage shows after a reply", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
                }
            }
        }
        Hint("Google sign-in on desktop is Phase 1.4 of the roadmap") {
            Text("Sign in with Google — coming soon", color = J.TextFaint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        }
    }
}
