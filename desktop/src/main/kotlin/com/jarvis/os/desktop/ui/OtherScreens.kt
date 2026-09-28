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
import com.jarvis.os.memory.MemoryFormat
import com.jarvis.os.ui.components.OrbPreview
import com.jarvis.os.ui.components.ThemeBackdrop
import com.jarvis.os.ui.theme.BackdropStyle
import com.jarvis.os.ui.theme.JarvisPalette

@Composable
private fun Page(title: String, subtitle: String, scroll: Boolean = false, content: @Composable () -> Unit) {
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
private fun Card(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.clip(shape).background(J.Card)
            .border(if (selected) 2.dp else 1.dp, if (selected) J.Accent else J.CardBorder, shape)
            .then(if (onClick != null) Modifier.clicky(onClick = onClick) else Modifier),
    ) { content() }
}

/**
 * The phone's theme + world picker, on the desktop. Only the SELECTED orb moves:
 * the phone's picker once ran every preview live and lagged badly enough to be
 * reported twice (see HudOrb's `animated` parameter). World thumbnails are stills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(appearance: DesktopPrefs.Appearance, onChange: (DesktopPrefs.Appearance) -> Unit) {
    Page("Themes", "Each theme is a different JARVIS — its own orb, its own colour and its own world behind it. Same themes as the phone.", scroll = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            JarvisPalette.entries.forEach { p ->
                val selected = p == appearance.palette
                Card(Modifier.width(236.dp), selected = selected, onClick = { onChange(DesktopPrefs.Appearance(p, "")) }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        OrbPreview(palette = p, size = 150.dp, animated = selected)
                        Spacer(Modifier.height(12.dp))
                        Text(p.displayName.uppercase(), color = if (selected) p.accent else J.Text, fontSize = 13.sp, fontFamily = J.Display, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(p.blurb, color = J.TextDim, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        Spacer(Modifier.height(34.dp))
        Text("WORLD", color = J.Text, fontSize = 14.sp, fontFamily = J.Display, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text("What JARVIS sits in. “Theme's own” follows the theme you pick.", color = J.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        val current = appearance.backdrop
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BackdropStyle.entries.forEach { b ->
                val selected = b == current
                val own = b == BackdropStyle.defaultFor(appearance.palette.orbStyle)
                Card(Modifier.width(196.dp), selected = selected, onClick = { onChange(appearance.copy(backdropId = if (own) "" else b.id)) }) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) {
                            ThemeBackdrop(palette = appearance.palette, backdrop = b, thumbnail = true, live = false)
                        }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(b.displayName, color = if (selected) J.Accent else J.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (own) "Theme's own" else b.blurb, color = J.TextDim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(a: DesktopAssistant) {
    Page("Memory", "What JARVIS keeps about you. It rides on every message, so keep it to what matters. Stored on this laptop.") {
        if (a.facts.isEmpty()) {
            Text("Nothing remembered yet. In chat, say “remember that …”.", color = J.TextMuted, fontSize = 15.sp)
            return@Page
        }
        LazyColumn(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(a.facts, key = { it }) { fact ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Masked like the phone's memory screen: a remembered number never sits in plain view.
                        Text(MemoryFormat.masked(fact), color = J.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Hint("Forget this") {
                            Icon(
                                Icons.Outlined.Close, "Forget this", tint = J.TextDim,
                                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clicky { a.forget(fact) }.padding(7.dp),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Forget everything", color = Color(0xFFFF8A8A), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clicky { a.forgetEverything() }.padding(8.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    Page("Settings", "How this laptop connects to JARVIS.") {
        Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow("Server", if (ProxyClient.isConfigured()) "Connected · ${BuildConfig.WORKER_URL.removePrefix("https://")}" else "Not configured")
            SettingRow("Account", "Guest (anonymous) · ${Identity.plan()} plan · Google sign-in arrives in Phase 1.4")
            SettingRow("Build secrets", "%USERPROFILE%\\.gradle\\gradle.properties — PROXY_SECRET, FIREBASE_WEB_API_KEY. Rebuild after changing.")
            SettingRow("Your data", AppDirs.root.absolutePath + " — conversations, memory, identity, appearance")
            SettingRow("Version", "Desktop ${BuildConfig.VERSION_NAME}")
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
