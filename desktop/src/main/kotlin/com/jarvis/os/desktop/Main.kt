package com.jarvis.os.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jarvis.os.desktop.ui.ChatScreen
import com.jarvis.os.desktop.ui.ComingSoon
import com.jarvis.os.desktop.ui.J
import com.jarvis.os.desktop.ui.JarvisTheme
import com.jarvis.os.desktop.ui.MemoryScreen
import com.jarvis.os.desktop.ui.Screen
import com.jarvis.os.desktop.ui.SettingsScreen
import com.jarvis.os.desktop.ui.Sidebar
import com.jarvis.os.desktop.ui.TodayRail
import java.awt.Dimension

fun main() = application {
    val scope = rememberCoroutineScope()
    val assistant = remember { DesktopAssistant(scope) }
    var screen by remember { mutableStateOf(Screen.Chat) }
    val composerFocus = remember { FocusRequester() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "JARVIS",
        state = rememberWindowState(size = DpSize(1280.dp, 760.dp), position = WindowPosition(Alignment.Center)),
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.N) {
                assistant.newChat(); screen = Screen.Chat; true
            } else false
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(980, 640) }
        JarvisTheme {
            BoxWithConstraints(Modifier.fillMaxSize().background(J.Ground)) {
                val wide = maxWidth >= 1200.dp
                Row(Modifier.fillMaxSize()) {
                    Sidebar(assistant, screen) { screen = it }
                    Divider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (screen) {
                            Screen.Chat -> ChatScreen(assistant, composerFocus)
                            Screen.Memory -> MemoryScreen(assistant)
                            Screen.Settings -> SettingsScreen(assistant)
                            Screen.Tasks -> ComingSoon(screen, "PHASE 3", "Tell JARVIS to do something on this laptop — open apps, sort files, work a website — and watch each step here. It stops for your OK before anything it can't undo.")
                            Screen.Scheduled -> ComingSoon(screen, "PHASE 4", "Reminders and routines that run by themselves: “every weekday at 8, brief me”, “remind me at 6 to call mom”.")
                            Screen.Files -> ComingSoon(screen, "PHASE 3", "Documents JARVIS makes for you — PDFs, notes, summaries. (Already on the phone; coming to the laptop with Tasks.)")
                            Screen.Automations -> ComingSoon(screen, "PHASE 5", "Your devices working together: “on my phone, set an alarm” from the laptop, and the other way round.")
                        }
                    }
                    if (wide && screen == Screen.Chat) {
                        Divider()
                        TodayRail(assistant) { screen = Screen.Memory }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Divider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(J.Hairline))
}
