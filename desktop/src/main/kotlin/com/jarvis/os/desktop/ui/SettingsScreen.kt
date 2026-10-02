package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.ai.UsageStats
import com.jarvis.os.desktop.AppDirs
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.DesktopPrefs
import com.jarvis.os.desktop.GoogleSignIn
import com.jarvis.os.desktop.StartWithWindows

/**
 * What a person can actually change, in the order they would look for it. Nothing here is for a developer:
 * no build secrets, no OAuth setup, no file paths unless they sit behind "Advanced". Things that cannot work in
 * this build (Google sign-in without a client) are hidden rather than explained, because an instruction for the
 * person who built the app is noise to the person using it.
 */
@Composable
fun SettingsScreen(
    a: DesktopAssistant,
    appearance: DesktopPrefs.Appearance,
    glass: Float,
    onGlass: (Float) -> Unit,
    onThemes: () -> Unit,
    onPermissions: () -> Unit,
    onMemory: () -> Unit,
) {
    Page("Settings", "Your JARVIS, your way.", scroll = true) {
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {

            Group("Account") {
                val acct = a.account
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    JarvisLogo(46.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (acct.isSignedIn) (acct.email ?: acct.label()) else "Guest", color = J.Text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            if (acct.isSignedIn) "Signed in with Google" else if (GoogleSignIn.isConfigured()) "Sign in to track your usage and use the same plan as your phone" else "Using JARVIS without an account",
                            color = J.TextDim, fontSize = 12.5.sp,
                        )
                    }
                    Chip(a.plan.replaceFirstChar { it.uppercase() } + " plan", J.Accent)
                    Spacer(Modifier.width(10.dp))
                    if (acct.isSignedIn) Action("Sign out", destructive = true) { a.signOut() }
                    else if (GoogleSignIn.isConfigured()) Action(if (a.signingIn) "Waiting for Google…" else "Sign in with Google") { if (!a.signingIn) a.signInWithGoogle() }
                }
                a.usage?.let { u ->
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Today's allowance", color = J.Text, fontSize = 14.sp)
                            Text("${UsageStats.format(u.remaining)} left of ${UsageStats.format(u.cap)}. It refills every day.", color = J.TextDim, fontSize = 12.5.sp)
                        }
                        Box(Modifier.width(160.dp)) { Meter(u.remaining.toFloat() / u.cap.coerceAtLeast(1), Modifier.fillMaxWidth()) }
                    }
                }
            }

            Group("Voice") {
                SwitchRow("Wake word", "Say “Jarvis” to start talking, hands-free. The word is detected on this laptop; nothing is sent until you say it.", a.wakeWordOn) { a.wakeWordOn = it }
                Divider()
                SwitchRow("Read every reply aloud", "On: JARVIS speaks all of its answers. Off: it only speaks answers to questions you spoke.", a.speakAllReplies) { a.speakAllReplies = it }
                Divider()
                InfoRow("Push to talk", "Hold Ctrl and press Space anywhere in the JARVIS window.", "Ctrl + Space")
            }

            Group("Shortcuts and startup") {
                SwitchRow(
                    "Quick bar",
                    when {
                        !a.quickBarOn -> "A small box that opens over any app so you can ask JARVIS without switching windows."
                        a.quickKey != null -> "Press ${a.quickKey} anywhere to ask JARVIS without switching windows."
                        else -> "Couldn't claim a shortcut: Alt+Space and Ctrl+Alt+J are both used by other apps."
                    },
                    a.quickBarOn,
                ) { a.quickBarOn = it }
                Divider()
                var startOn by remember { mutableStateOf(StartWithWindows.available && StartWithWindows.isOn()) }
                SwitchRow(
                    "Start with Windows",
                    when {
                        !StartWithWindows.available -> "Available in the installed JARVIS app."
                        else -> "JARVIS waits quietly in the tray when you sign in, so reminders and routines still fire."
                    },
                    startOn, enabled = StartWithWindows.available,
                ) { on -> if (StartWithWindows.set(on)) startOn = StartWithWindows.isOn() }
            }

            Group("Appearance") {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Theme", color = J.Text, fontSize = 14.sp)
                        Text("${appearance.palette.displayName} · ${appearance.desktopWorld.displayName}", color = J.TextDim, fontSize = 12.5.sp)
                    }
                    Action("Change") { onThemes() }
                }
                if (appearance.desktopWorld.seeThrough) {
                    Divider()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Glass opacity", color = J.Text, fontSize = 14.sp)
                                Text("How dark the see-through tint is. Lower shows more of your desktop; higher makes text easier to read.", color = J.TextDim, fontSize = 12.5.sp)
                            }
                            Text("${(glass * 100).toInt()}%", color = J.Accent, fontSize = 12.sp, fontFamily = J.Mono)
                        }
                        Slider(
                            value = glass, onValueChange = onGlass, valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = J.Accent, activeTrackColor = J.Accent, inactiveTrackColor = J.Accent.copy(alpha = 0.2f)),
                        )
                    }
                }
            }

            Group("Privacy and your data") {
                InfoRow("Where your data lives", "Your chats, memory and tasks are stored in a folder on this laptop. Only what you send to JARVIS is processed by its server.", null)
                Divider()
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your JARVIS folder", color = J.Text, fontSize = 14.sp)
                        Text("Open it to back up or inspect everything JARVIS has stored.", color = J.TextDim, fontSize = 12.5.sp)
                    }
                    Action("Open folder") { runCatching { java.awt.Desktop.getDesktop().open(AppDirs.root) } }
                }
                Divider()
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("What JARVIS may do", color = J.Text, fontSize = 14.sp)
                        Text("Files it may read, screen capture and other permissions. Nothing is allowed until you say so.", color = J.TextDim, fontSize = 12.5.sp)
                    }
                    Action("Manage") { onPermissions() }
                }
                Divider()
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("What JARVIS remembers", color = J.Text, fontSize = 14.sp)
                        Text(if (a.facts.isEmpty()) "Nothing yet. Say “remember that …” and it will show up here." else "${a.facts.size} thing${if (a.facts.size == 1) "" else "s"} remembered. You can read and delete every one.", color = J.TextDim, fontSize = 12.5.sp)
                    }
                    Action("View") { onMemory() }
                }
            }

            // Only where it can work in this build; otherwise the whole group is simply absent.
            if (GoogleSignIn.isConfigured()) {
                Group("Connections") {
                    GoogleRow(a)
                    Divider()
                    SyncRow(a)
                }
            }

            About()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun About() {
    var advanced by remember { mutableStateOf(false) }
    Group("About") {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            JarvisLogo(54.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("JARVIS for desktop", color = J.Text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Version ${BuildConfig.VERSION_NAME}", color = J.TextDim, fontSize = 12.5.sp)
            }
            Chip(if (ProxyClient.isConfigured()) "Connected" else "Offline", if (ProxyClient.isConfigured()) J.Green else J.Red)
        }
        Divider()
        Row(Modifier.fillMaxWidth().clicky { advanced = !advanced }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced", color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (advanced) "HIDE" else "SHOW", color = J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp)
        }
        if (advanced) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server  ·  ${if (ProxyClient.isConfigured()) BuildConfig.WORKER_URL.removePrefix("https://") else "not configured"}", color = J.TextMuted, fontSize = 12.sp, fontFamily = J.Mono)
                Text("Data    ·  ${AppDirs.root.absolutePath}", color = J.TextMuted, fontSize = 12.sp, fontFamily = J.Mono)
            }
        }
    }
}

// ── small pieces ───────────────────────────────────────────────────────────────

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), color = J.TextDim, fontSize = 11.sp, fontFamily = J.Display, letterSpacing = 2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { content() } }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(J.Hairline))
}

@Composable
private fun SwitchRow(title: String, description: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) J.Text else J.TextDim, fontSize = 14.sp)
            Text(description, color = J.TextDim, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Text(
            if (on) "ON" else "OFF", color = if (on) J.OnAccent else J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp,
            modifier = Modifier.clip(HudShapeSmall).background(if (on) J.Accent else Color.Transparent)
                .border(1.dp, if (on) J.Accent else J.Border, HudShapeSmall)
                .then(if (enabled) Modifier.clicky { onChange(!on) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun InfoRow(title: String, description: String, trailing: String?) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = J.Text, fontSize = 14.sp)
            Text(description, color = J.TextDim, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        if (trailing != null) Chip(trailing, J.TextMuted, mono = true)
    }
}

@Composable
private fun Chip(text: String, color: Color, mono: Boolean = false) {
    Text(
        text, color = color, fontSize = if (mono) 11.sp else 11.5.sp, fontFamily = if (mono) J.Mono else null,
        modifier = Modifier.clip(HudShapeSmall).border(1.dp, color.copy(alpha = 0.5f), HudShapeSmall).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Action(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val c = if (destructive) Color(0xFFFF8A8A) else J.Accent
    Text(
        label.uppercase(), color = c, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.2.sp,
        modifier = Modifier.clip(HudShapeSmall).border(1.dp, c.copy(alpha = 0.6f), HudShapeSmall).clicky(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
