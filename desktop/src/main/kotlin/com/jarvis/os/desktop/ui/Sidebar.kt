package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Where the app can go. Five places sit in the sidebar; the four occasional ones (Settings, Themes, Permissions,
 * the Activity log) live in the account menu at its foot — they used to be four unlabeled icons.
 * "Routines" is the old Scheduled screen: reminders and routines are one idea to the person using them
 * (the placeholder "Automations" item that sat beside it is gone until it can do something).
 */
enum class Screen(val label: String, val icon: ImageVector, val primary: Boolean = true) {
    Chat("Chat", Icons.Outlined.ChatBubbleOutline),
    Tasks("Tasks", Icons.Outlined.TaskAlt),
    Memory("Memory", Icons.Outlined.AutoAwesome),
    Files("Files", Icons.Outlined.Description),
    Scheduled("Routines", Icons.Outlined.Bolt),
    // In the account menu, in this order.
    Settings("Settings", Icons.Outlined.Tune, primary = false),
    Appearance("Themes", Icons.Outlined.Palette, primary = false),
    Permissions("Permissions", Icons.Outlined.Shield, primary = false),
    Activity("Activity log", Icons.Outlined.History, primary = false),
}

@Composable
fun Sidebar(a: DesktopAssistant, screen: Screen, onHome: () -> Unit, onSearch: () -> Unit, onScreen: (Screen) -> Unit) {
    Column(Modifier.width(264.dp).fillMaxHeight().background(J.Glass).padding(top = 18.dp)) {
        // The brand is the way home, as a logo is on any desktop app.
        Hint("Home") {
            Row(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp).clicky(onHome),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JarvisLogo(30.dp)
                Spacer(Modifier.width(12.dp))
                Text("JARVIS", color = J.Text, fontSize = 14.sp, fontFamily = J.Display, letterSpacing = 4.sp)
            }
        }

        // The one primary action, and search beside it as a button rather than a second full-width bar.
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(42.dp).clip(HudShape)
                    .background(J.Accent.copy(alpha = 0.12f))
                    .border(1.dp, J.Accent.copy(alpha = 0.5f), HudShape)
                    .clicky { a.newChat(); onScreen(Screen.Chat) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = J.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("New chat", color = J.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Ctrl N", color = J.TextDim, fontSize = 11.sp, fontFamily = J.Mono)
            }
            Hint("Search everything (Ctrl K)") {
                Box(
                    Modifier.size(42.dp).clip(HudShapeSmall).border(1.dp, J.Border, HudShapeSmall).clicky(onSearch),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Search, "Search everything", tint = J.TextMuted, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(14.dp))

        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Screen.entries.filter { it.primary }.forEach { s ->
                val badge = when (s) {
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
        Modifier.fillMaxWidth().height(38.dp).clip(HudShapeSmall)
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

/** One account card (who you are, your plan, what is left today) and one menu for everything else. */
@Composable
private fun AccountFooter(a: DesktopAssistant, screen: Screen, onScreen: (Screen) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(HudShape).background(J.Card)
                .border(1.dp, J.CardBorder, HudShape).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(J.Accent.copy(alpha = 0.12f)).border(1.dp, J.Accent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(a.account.initial.toString(), color = J.Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.account.label(), color = J.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    val pro = a.plan == "pro"
                    Text(
                        a.plan.uppercase(), color = if (pro) J.OnAccent else J.TextDim, fontSize = 9.5.sp, fontFamily = J.Mono, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(if (pro) J.Accent else Color.Transparent)
                            .then(if (pro) Modifier else Modifier.border(1.dp, J.Border, RoundedCornerShape(2.dp)))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                val u = a.usage
                if (u != null) {
                    Box(Modifier.padding(top = 7.dp, bottom = 5.dp).fillMaxWidth().height(3.dp).background(J.Accent.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxWidth(u.remaining.toFloat() / u.cap.coerceAtLeast(1)).height(3.dp).background(J.Accent))
                    }
                    Text("${compactTokens(u.remaining)} tokens left today", color = J.TextDim, fontSize = 10.5.sp, fontFamily = J.Mono)
                } else {
                    Text("Checking your allowance…", color = J.TextDim, fontSize = 10.5.sp, fontFamily = J.Mono, modifier = Modifier.padding(top = 4.dp))
                }
                // Sign-in lives in the card for guests, only once this build has a Google client.
                if (!a.account.isSignedIn && GoogleSignIn.isConfigured()) {
                    Text(
                        if (a.signingIn) "Finish signing in in your browser…" else "Sign in with Google ›",
                        color = if (a.signingIn) J.TextDim else J.Accent, fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 6.dp).then(if (a.signingIn) Modifier else Modifier.clicky { a.signInWithGoogle() }),
                    )
                }
            }
            Box {
                Hint("Settings, themes and more") {
                    Box(
                        Modifier.size(32.dp).clip(HudShapeSmall)
                            .background(if (menu) J.Accent.copy(alpha = 0.16f) else Color.Transparent)
                            .border(1.dp, J.Border, HudShapeSmall)
                            .clicky { menu = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.MoreHoriz, "Account and settings menu", tint = J.TextMuted, modifier = Modifier.size(18.dp)) }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = J.Solid) {
                    Screen.entries.filter { !it.primary }.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, color = if (s == screen) J.Accent else J.Text, fontSize = 14.sp) },
                            leadingIcon = { Icon(s.icon, null, tint = if (s == screen) J.Accent else J.TextMuted, modifier = Modifier.size(18.dp)) },
                            onClick = { menu = false; onScreen(s) },
                        )
                    }
                    HorizontalDivider(color = J.Hairline)
                    DropdownMenuItem(
                        text = { Text("This laptop · connected", color = J.TextDim, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Computer, null, tint = J.Green, modifier = Modifier.size(18.dp)) },
                        enabled = false,
                        onClick = {},
                    )
                    if (a.account.isSignedIn) DropdownMenuItem(
                        text = { Text("Sign out", color = Color(0xFFFF8A8A), fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Logout, null, tint = Color(0xFFFF8A8A), modifier = Modifier.size(18.dp)) },
                        onClick = { menu = false; a.signOut() },
                    )
                }
            }
        }
    }
}

/** "1.99M", "743", "52k" — a token count short enough for a small card. */
internal fun compactTokens(n: Int): String = when {
    // Rounded DOWN: "1.99M left" must never read as "2M" when some has been used.
    n >= 1_000_000 -> (n / 10_000).let { h -> "${h / 100}." + (h % 100).toString().padStart(2, '0') + "M" }.replace(".00M", "M")
    n >= 10_000 -> "${n / 1000}k"
    else -> UsageStats.format(n)
}
