package com.jarvis.os.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.desktop.AppDirs
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.DesktopPrefs
import com.jarvis.os.desktop.GoogleSignIn
import com.jarvis.os.desktop.brain.TaskDates
import com.jarvis.os.desktop.StartWithWindows
import com.jarvis.os.memory.MemoryFormat
import androidx.compose.runtime.CompositionLocalProvider
import com.jarvis.os.desktop.DesktopWorld
import com.jarvis.os.desktop.ui.stark.DesktopWorldView
import com.jarvis.os.ui.theme.LocalPalette
import com.jarvis.os.voice.OrbState
import com.jarvis.os.ui.theme.JarvisPalette

@Composable
internal fun Page(title: String, subtitle: String, scroll: Boolean = false, content: @Composable () -> Unit) {
    val base = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp)
    Column(if (scroll) base.verticalScroll(rememberScrollState()) else base) {
        Text(title.uppercase(), color = J.Text, fontSize = 22.sp, fontFamily = J.Display, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = J.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.clip(shape).background(J.Card)
            .border(if (selected) 2.dp else 1.dp, if (selected) J.Accent else J.CardBorder, shape)
            .then(if (onClick != null) Modifier.clicky(onClick = onClick) else Modifier),
    ) { content() }
}

/**
 * The theme + world picker. A theme card IS its world with the orb sitting in it, so the card shows
 * what you will actually get. Only the SELECTED orb moves (the phone's picker once ran every
 * preview live and lagged badly enough to be reported twice); every world thumbnail is a still.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(appearance: DesktopPrefs.Appearance, onChange: (DesktopPrefs.Appearance) -> Unit) {
    Page("Themes", "Each theme is a different JARVIS: its own colour, its own orb and its own world. Pick a world separately below if you like.", scroll = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            JarvisPalette.entries.forEach { p ->
                val selected = p == appearance.palette
                Card(Modifier.width(300.dp), selected = selected, onClick = { onChange(DesktopPrefs.Appearance(p, "")) }) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)), contentAlignment = Alignment.Center) {
                            DesktopWorldView(DesktopWorld.ownFor(p), p, live = false, thumbnail = true)
                            CompositionLocalProvider(LocalPalette provides p) {
                                ReactorOrb(size = 150.dp, state = OrbState.Idle, labels = false, animated = selected)
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(p.displayName.uppercase(), color = if (selected) p.accent else J.Text, fontSize = 13.sp, fontFamily = J.Display, letterSpacing = 1.5.sp)
                            Spacer(Modifier.height(5.dp))
                            Text(p.blurb, color = J.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(34.dp))
        Text("WORLD", color = J.Text, fontSize = 14.sp, fontFamily = J.Display, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text("What JARVIS sits in. \"Theme's own\" follows the theme you pick; any world works under any theme.", color = J.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        val current = appearance.desktopWorld
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopWorld.entries.forEach { dw ->
                val selected = current == dw
                val own = dw == DesktopWorld.ownFor(appearance.palette)
                WorldCard(selected, onClick = { onChange(appearance.copy(backdropId = if (own) "" else dw.id)) }, title = dw.displayName, subtitle = if (own) "Theme's own" else dw.blurb) {
                    DesktopWorldView(dw, appearance.palette, live = false, thumbnail = true)
                }
            }
        }
    }
}

@Composable
private fun WorldCard(selected: Boolean, onClick: () -> Unit, title: String, subtitle: String, preview: @Composable () -> Unit) {
    Card(Modifier.width(196.dp), selected = selected, onClick = onClick) {
        Column {
            Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) { preview() }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(title, color = if (selected) J.Accent else J.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = J.TextDim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
            }
        }
    }
}

@Composable
fun SettingsScreen(a: DesktopAssistant) {
    Page("Settings", "How this laptop connects to JARVIS.") {
        Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow("Server", if (ProxyClient.isConfigured()) "Connected · ${BuildConfig.WORKER_URL.removePrefix("https://")}" else "Not configured")
            val acct = a.account
            SettingRow(
                "Account",
                when {
                    acct.isSignedIn -> "${acct.email ?: acct.label()} (Google) · ${a.plan} plan"
                    GoogleSignIn.isConfigured() -> "Guest · ${a.plan} plan · sign in from the sidebar to use your Google account"
                    else -> "Guest · ${a.plan} plan · Google sign-in needs a Desktop OAuth client (see desktop/README.md)"
                },
            )
            if (acct.isSignedIn) {
                Text(
                    "Sign out of this laptop", color = Color(0xFFFF8A8A), fontSize = 13.sp,
                    modifier = Modifier.clip(HudShapeSmall).clicky { a.signOut() }.padding(8.dp),
                )
            }
            GoogleRow(a)
            SyncRow(a)
            ToggleRow(
                "Quick bar",
                when {
                    !a.quickBarOn -> "Off"
                    a.quickKey != null -> "Press ${a.quickKey} anywhere to ask JARVIS"
                    else -> "Couldn't get a key: Alt+Space and Ctrl+Alt+J are both taken by other apps"
                },
                a.quickBarOn,
            ) { a.quickBarOn = it }
            var startOn by remember { mutableStateOf(StartWithWindows.available && StartWithWindows.isOn()) }
            ToggleRow(
                "Start with Windows",
                when {
                    !StartWithWindows.available -> "Available in the installed JARVIS app (this is a development run)"
                    startOn -> "JARVIS starts in the tray when you sign in to Windows, so routines and reminders keep working"
                    else -> "Off. Turn on so routines and reminders keep working after a restart"
                },
                startOn, enabled = StartWithWindows.available,
            ) { on -> if (StartWithWindows.set(on)) startOn = StartWithWindows.isOn() }
            SettingRow("Build secrets", "%USERPROFILE%\\.gradle\\gradle.properties — PROXY_SECRET, FIREBASE_WEB_API_KEY. Rebuild after changing.")
            SettingRow("Your data", AppDirs.root.absolutePath + " — conversations, memory, identity, appearance")
            SettingRow("Version", "Desktop ${BuildConfig.VERSION_NAME}")
        }
    }
}

/**
 * Google Calendar + Gmail (Phase 6): connect / disconnect, and exactly what it allows. Honest
 * when the build has no Desktop OAuth client yet (the owner's one-time console step).
 */
@Composable
private fun GoogleRow(a: DesktopAssistant) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Calendar & Gmail", color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.width(150.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        a.googleEmail != null -> "Connected" + (a.googleEmail!!.takeIf { '@' in it }?.let { " as $it" } ?: "") +
                            ". JARVIS can read your calendar and email, add events and write drafts. It sends an email only when you approve it."
                        !GoogleSignIn.isConfigured() -> "Needs the Google “Desktop app” sign-in client in this build (a one-time setup in Google Cloud: see desktop/README.md)."
                        else -> "Let JARVIS read your calendar and email, add events, and draft replies (sending always asks you first). Opens Google's own page."
                    },
                    color = J.Text, fontSize = 14.sp, lineHeight = 20.sp,
                )
                a.googleError?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp) }
            }
            val label = when {
                a.googleBusy -> "WAITING…"
                a.googleEmail != null -> "DISCONNECT"
                else -> "CONNECT"
            }
            val enabled = !a.googleBusy && (a.googleEmail != null || GoogleSignIn.isConfigured())
            Text(
                label, color = if (a.googleEmail == null && enabled) J.OnAccent else J.Text, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 12.dp).clip(HudShapeSmall)
                    .background(if (a.googleEmail == null && enabled) J.Accent else Color.Transparent)
                    .border(1.dp, if (enabled) J.Accent else J.Border, HudShapeSmall)
                    .then(if (enabled) Modifier.clicky { if (a.googleEmail != null) a.disconnectGoogle() else a.connectGoogle() } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Sync across devices (Phase 7): off by default (AGENT_PLAN §8 decision 4 — opt-in), and
 * only meaningful once signed in with Google, since it is the account that ties the phone
 * and laptop together — an anonymous laptop has no "other device" to share with.
 */
@Composable
private fun SyncRow(a: DesktopAssistant) {
    val enabled = a.account.isSignedIn
    val status = when {
        !enabled -> "Needs Google sign-in above — an account is what ties your phone and laptop together"
        !a.syncOn -> "Off. Turn on to share tasks, reminders, notes and memory with your other signed-in devices"
        a.syncing -> "Syncing…"
        a.syncError != null -> "On, but the last attempt failed: ${a.syncError}"
        a.lastSyncedAt != null -> "On · last synced ${TaskDates.label(a.lastSyncedAt!!, System.currentTimeMillis())}"
        else -> "On · syncing shortly"
    }
    ToggleRow("Sync across devices", status, a.syncOn, enabled = enabled) { a.syncOn = it }
}

@Composable
private fun ToggleRow(label: String, value: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.width(150.dp))
            Text(value, color = J.Text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            Text(
                if (on) "ON" else "OFF", color = if (on) J.OnAccent else J.TextDim, fontSize = 10.sp, fontFamily = J.Display, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 12.dp).clip(HudShapeSmall).background(if (on) J.Accent else Color.Transparent)
                    .border(1.dp, if (on) J.Accent else J.Border, HudShapeSmall)
                    .then(if (enabled) Modifier.clicky { onChange(!on) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Text(label, color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.width(150.dp))
            Text(value, color = J.Text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** An honest placeholder for a section whose phase has not landed yet — still with a live orb. */
@Composable
fun ComingSoon(screen: Screen, phase: String, what: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 540.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StillOrb(120.dp)
            Spacer(Modifier.height(18.dp))
            Text(screen.label.uppercase(), color = J.Text, fontSize = 20.sp, fontFamily = J.Display, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            Pill(phase, fg = J.Accent, bg = J.Accent.copy(alpha = 0.14f), mono = true)
            Spacer(Modifier.height(14.dp))
            Text(what, color = J.TextMuted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
        }
    }
}
