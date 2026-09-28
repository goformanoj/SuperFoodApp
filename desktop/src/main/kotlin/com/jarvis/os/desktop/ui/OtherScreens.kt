package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.os.BuildConfig
import com.jarvis.os.ai.Identity
import com.jarvis.os.ai.ProxyClient
import com.jarvis.os.desktop.AppDirs
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.memory.MemoryFormat

@Composable
private fun Page(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 26.dp)) {
        Text(title, color = J.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = J.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(22.dp))
        content()
    }
}

@Composable
fun MemoryScreen(a: DesktopAssistant) {
    Page("Memory", "What JARVIS keeps about you. It rides on every message, so keep it to what matters. Stored on this laptop.") {
        if (a.facts.isEmpty()) {
            Text("Nothing remembered yet. In chat, say “remember that …”.", color = J.TextMuted, fontSize = 15.sp)
            return@Page
        }
        LazyColumn(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(a.facts, key = { it }) { fact ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(J.Surface)
                        .border(1.dp, J.Hairline, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Masked like the phone's memory screen: a remembered number never sits in plain view.
                    Text(MemoryFormat.masked(fact), color = J.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Hint("Forget this") {
                        Icon(
                            Icons.Outlined.Close, "Forget this", tint = J.TextDim,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { a.forget(fact) }.padding(7.dp),
                        )
                    }
                }
            }
            item {
                Text(
                    "Forget everything", color = Color(0xFFFF8A8A), fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { a.forgetEverything() }.padding(8.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(a: DesktopAssistant) {
    Page("Settings", "How this laptop connects to JARVIS.") {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingRow("Server", if (ProxyClient.isConfigured()) "Connected · ${BuildConfig.WORKER_URL.removePrefix("https://")}" else "Not configured")
            SettingRow("Account", "Guest (anonymous) · ${Identity.plan()} plan · Google sign-in coming in Phase 1.4")
            SettingRow("Build secrets", "%USERPROFILE%\\.gradle\\gradle.properties — PROXY_SECRET, FIREBASE_WEB_API_KEY. Rebuild after changing.")
            SettingRow("Your data", AppDirs.root.absolutePath + " — conversations, memory, identity")
            SettingRow("Version", "Desktop ${BuildConfig.VERSION_NAME}")
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(J.Surface)
            .border(1.dp, J.Hairline, RoundedCornerShape(12.dp)).padding(16.dp),
    ) {
        Text(label, color = J.TextMuted, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        Text(value, color = J.Text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** An honest placeholder for a section whose phase has not landed yet. */
@Composable
fun ComingSoon(screen: Screen, phase: String, what: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(J.Cyan.copy(alpha = 0.10f))
                    .border(1.dp, J.Cyan.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(screen.icon, null, tint = J.Cyan, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(20.dp))
            Text(screen.label, color = J.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Pill(phase, fg = J.CyanSoft, bg = J.Cyan.copy(alpha = 0.10f), mono = true)
            Spacer(Modifier.height(14.dp))
            Text(what, color = J.TextDim, fontSize = 15.sp, lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
