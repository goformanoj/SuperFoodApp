package com.jarvis.os.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.Text
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jarvis.os.desktop.ui.ActivityScreen
import com.jarvis.os.desktop.ui.FilesScreen
import com.jarvis.os.desktop.ui.ScheduledScreen
import com.jarvis.os.desktop.ui.QuickBar
import com.jarvis.os.desktop.ui.AppearanceScreen
import com.jarvis.os.desktop.ui.SearchOverlay
import com.jarvis.os.desktop.ui.TasksScreen
import com.jarvis.os.desktop.ui.ChatScreen
import com.jarvis.os.desktop.ui.ComingSoon
import com.jarvis.os.desktop.ui.DesktopTheme
import com.jarvis.os.desktop.ui.HudGrid
import com.jarvis.os.desktop.ui.J
import com.jarvis.os.desktop.ui.MemoryScreen
import com.jarvis.os.desktop.ui.Screen
import com.jarvis.os.desktop.ui.SettingsScreen
import com.jarvis.os.desktop.ui.Sidebar
import com.jarvis.os.desktop.ui.TodayRail
import com.jarvis.os.desktop.ui.hasConversation
import com.jarvis.os.ui.components.ThemeBackdrop
import com.jarvis.os.ui.theme.JarvisPalette
import java.awt.Dimension

/**
 * Flags (for development and screenshots — no effect on normal use):
 *   --home            open on a fresh chat (the cockpit) instead of the last conversation
 *   --screen=<name>   open on a section, e.g. --screen=appearance
 *   --theme=<id>      preview a theme for this run without saving it (arc, forge, nebula, orbit)
 *   --attach=<path>   start with that file in the composer, as if it had been dropped in
 *   --quickbar        open the Quick bar at start (to see it without pressing the key)
 *   --background      start hidden in the tray (what "Start with Windows" launches)
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val assistant = remember { DesktopAssistant(scope).also { if ("--home" in args) it.newChat() } }
    val telemetry = remember { Telemetry(scope).also { it.start() } }
    val prefs = remember { DesktopPrefs() }
    var appearance by remember {
        val saved = prefs.load()
        val preview = args.firstOrNull { it.startsWith("--theme=") }?.substringAfter("=")
        mutableStateOf(if (preview != null) saved.copy(palette = JarvisPalette.fromId(preview), backdropId = "") else saved)
    }
    var screen by remember {
        val name = args.firstOrNull { it.startsWith("--screen=") }?.substringAfter("=")
        mutableStateOf(Screen.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Screen.Chat)
    }
    val composerText = remember { mutableStateOf("") }
    val composerFocus = remember { FocusRequester() }
    fun goHome() { assistant.newChat(); screen = Screen.Chat }
    var searchOpen by remember { mutableStateOf(false) }
    fun openConversation(id: String) { assistant.select(id); screen = Screen.Chat }

    // Reopen where the user left it: same size, same place, maximised or not.
    val geometry = remember { prefs.loadGeometry() }
    val windowState = rememberWindowState(
        placement = if (geometry.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        size = DpSize(geometry.width.dp, geometry.height.dp),
        position = if (geometry.x != null && geometry.y != null) WindowPosition(geometry.x.dp, geometry.y.dp) else WindowPosition(Alignment.Center),
    )
    fun saveAndExit() {
        val max = windowState.placement == WindowPlacement.Maximized
        val pos = windowState.position
        prefs.saveGeometry(
            DesktopPrefs.Geometry(
                width = windowState.size.width.value,
                height = windowState.size.height.value,
                x = if (pos.isSpecified) pos.x.value else null,
                y = if (pos.isSpecified) pos.y.value else null,
                maximized = max,
            ),
        )
        GlobalHotkey.stop()
        assistant.shutdownVoice()
        exitApplication()
    }

    // ── Always there (AGENT_PLAN §6): the tray + reminders ─────────────────────
    // Closing the window HIDES it: JARVIS keeps running in the tray so reminders can
    // pop up. Quit from the tray menu. The first hide explains this once.
    val trayState = rememberTrayState()
    var windowVisible by remember { mutableStateOf(StartWithWindows.BACKGROUND_FLAG !in args) }

    // ── The Quick bar (AGENT_PLAN §6): one key anywhere in Windows ─────────────
    var quickOpen by remember { mutableStateOf("--quickbar" in args) }
    fun toggleQuick() {
        if (!quickOpen) assistant.quickReset()
        quickOpen = !quickOpen
    }
    LaunchedEffect(assistant.quickBarOn) {
        GlobalHotkey.stop()
        assistant.quickKey = if (!assistant.quickBarOn) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // The key arrives on the hotkey thread; state changes belong on the UI thread.
            GlobalHotkey.start { javax.swing.SwingUtilities.invokeLater { toggleQuick() } }
        }
        prefs.setFlag(PREF_QUICKBAR_OFF, !assistant.quickBarOn)
    }
    QuickBar(
        assistant, appearance.palette, quickOpen,
        saved = remember { prefs.loadQuick() }, onSave = { prefs.saveQuick(it) },
        onClose = { quickOpen = false },
        onOpenMain = { quickOpen = false; windowVisible = true; screen = Screen.Chat },
    )
    var toldAboutTray by remember { mutableStateOf(prefs.flag(PREF_TOLD_TRAY)) }
    Tray(
        icon = remember(appearance.palette) { ReactorIcon(appearance.palette) },
        state = trayState,
        tooltip = "JARVIS",
        onAction = { windowVisible = true },
        menu = {
            Item("Open JARVIS", onClick = { windowVisible = true })
            Item("Quick bar" + (assistant.quickKey?.let { " ($it)" } ?: ""), onClick = { toggleQuick() })
            Item("New chat", onClick = { goHome(); windowVisible = true })
            Separator()
            Item("Quit JARVIS", onClick = ::saveAndExit)
        },
    )
    // A screenshot steps JARVIS out of the way for a moment, then brings it back.
    LaunchedEffect(Unit) {
        args.firstOrNull { it.startsWith("--attach=") }?.substringAfter("=")?.let { assistant.attach(listOf(java.io.File(it))) }
        assistant.windowControl = object : DesktopAssistant.WindowControl {
            override val visible: Boolean get() = windowVisible
            override fun hide() { windowVisible = false }
            override fun show() { windowVisible = true }
        }
    }
    fun hideToTray() {
        windowVisible = false
        if (!toldAboutTray) {
            trayState.sendNotification(Notification("JARVIS is still running", "It stays in the tray so your reminders can pop up. Right-click the icon to quit."))
            toldAboutTray = true
            prefs.setFlag(PREF_TOLD_TRAY, true)
        }
    }
    // Reminders: checked every 15 s. A reminder that came due while the laptop was off
    // pops up on the next start, marked late — never silently dropped.
    LaunchedEffect(Unit) {
        while (true) {
            assistant.dueReminders().forEach { r ->
                val late = System.currentTimeMillis() - r.at > 5 * 60_000
                trayState.sendNotification(
                    Notification(if (late) "Reminder (missed while JARVIS was off)" else "Reminder", r.text, Notification.Type.Info),
                )
                if (assistant.speakAllReplies) assistant.speakText("Reminder: " + r.text)
                assistant.markReminderDelivered(r)
            }
            assistant.refreshReminders()
            // Routines (S9): run by themselves; the result arrives like a reminder, and is
            // spoken when the routine says so.
            assistant.runDueRoutines().forEach { run ->
                val body = DesktopTurn.plain(run.text)
                trayState.sendNotification(
                    Notification(run.routine.name + if (run.late) " (a little late)" else "", body.take(240), if (run.ok) Notification.Type.Info else Notification.Type.Warning),
                )
                if (run.ok && run.routine.speak) assistant.speakText(body)
            }
            kotlinx.coroutines.delay(15_000)
        }
    }

    // Voice choices persist; the wake listener follows state (see DesktopAssistant.syncWake).
    LaunchedEffect(Unit) {
        assistant.wakeWordOn = prefs.flag(PREF_WAKE)
        assistant.quickBarOn = !prefs.flag(PREF_QUICKBAR_OFF)
        assistant.speakAllReplies = prefs.flag(PREF_SPEAK_ALL)
        snapshotFlow { Triple(assistant.wakeWordOn, assistant.speakAllReplies, assistant.voice to assistant.thinking) }
            .collect { (wakeOn, speakAll, _) ->
                prefs.setFlag(PREF_WAKE, wakeOn)
                prefs.setFlag(PREF_SPEAK_ALL, speakAll)
                assistant.syncWake()
            }
    }

    Window(
        onCloseRequest = ::hideToTray,
        visible = windowVisible,
        title = "JARVIS",
        icon = remember(appearance.palette) { ReactorIcon(appearance.palette) },
        state = windowState,
        onPreviewKeyEvent = { e ->
            when {
                e.type != KeyEventType.KeyDown -> false
                e.isCtrlPressed && e.key == Key.N -> { goHome(); true }
                e.isCtrlPressed && e.key == Key.K -> { searchOpen = true; true }
                // Push-to-talk from anywhere in the window.
                e.isCtrlPressed && e.key == Key.Spacebar -> { screen = Screen.Chat; assistant.toggleMic(); true }
                else -> false
            }
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(980, 640) }
        DesktopTheme(appearance.palette) {
            val home = screen == Screen.Chat && !hasConversation(assistant)

            // Drop files anywhere on the window: JARVIS reads them in and puts them in the composer.
            var dropHover by remember { mutableStateOf(false) }
            val dropTarget = remember {
                object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                    override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) { dropHover = true }
                    override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) { dropHover = false }
                    override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) { dropHover = false }
                    override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                        dropHover = false
                        val t = event.awtTransferable
                        if (!t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return false
                        val files = (t.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<java.io.File>()
                        if (files.isEmpty()) return false
                        assistant.attach(files)
                        screen = Screen.Chat
                        return true
                    }
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxSize().background(appearance.palette.background)
                    .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget),
            ) {
                val wide = maxWidth >= 1200.dp

                // The theme's world, behind EVERY screen — as on the phone. Live only on
                // Home, where nothing scrolls: behind a list its redraws would compete
                // with the scroll for the same frame budget.
                ThemeBackdrop(palette = appearance.palette, backdrop = appearance.backdrop, live = home)
                // The veil: Home IS the backdrop; screens with text need a surface to read on.
                if (!home) Box(Modifier.fillMaxSize().background(J.Veil))
                // The instrument grid and vignette over everything: the HUD's glass.
                HudGrid(Modifier.fillMaxSize())

                Row(Modifier.fillMaxSize()) {
                    Sidebar(assistant, screen, onHome = ::goHome, onSearch = { searchOpen = true }) { screen = it }
                    Divider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (screen) {
                            Screen.Chat -> ChatScreen(assistant, telemetry, composerText, composerFocus, onMemory = { screen = Screen.Memory }, onTasks = { screen = Screen.Tasks })
                            Screen.Memory -> MemoryScreen(assistant, ::openConversation)
                            Screen.Activity -> ActivityScreen(assistant)
                            Screen.Appearance -> AppearanceScreen(appearance) { appearance = it; prefs.save(it) }
                            Screen.Settings -> SettingsScreen(assistant)
                            Screen.Tasks -> TasksScreen(assistant, ::openConversation)
                            Screen.Scheduled -> ScheduledScreen(assistant, onOpenChat = { screen = Screen.Chat })
                            Screen.Files -> FilesScreen(assistant, onAsk = { screen = Screen.Chat }, onOpenConversation = ::openConversation)
                            Screen.Automations -> ComingSoon(screen, "PHASE 7", "Your devices working together: “on my phone, set an alarm” from the laptop, and the other way round.")
                        }
                    }
                    if (wide && screen == Screen.Chat && hasConversation(assistant)) {
                        Divider()
                        TodayRail(assistant, telemetry) { screen = Screen.Memory }
                    }
                }
                if (dropHover) {
                    Box(Modifier.fillMaxSize().background(Color(0xCC05080D)), contentAlignment = Alignment.Center) {
                        Text("DROP TO GIVE JARVIS THIS FILE", color = J.Accent, fontFamily = J.Display, fontSize = 18.sp, letterSpacing = 3.sp)
                    }
                }
                if (searchOpen) {
                    SearchOverlay(
                        assistant,
                        onOpen = { hit ->
                            searchOpen = false
                            when (hit.kind) {
                                "conversation" -> openConversation(hit.refId)
                                "task" -> screen = Screen.Tasks
                                "memory" -> screen = Screen.Memory
                                else -> screen = Screen.Files
                            }
                        },
                        onClose = { searchOpen = false },
                    )
                }
            }
        }
    }
}

private const val PREF_WAKE = "voice.wakeword"
private const val PREF_TOLD_TRAY = "ui.toldAboutTray"
private const val PREF_SPEAK_ALL = "voice.speakAll"
private const val PREF_QUICKBAR_OFF = "quickbar.off"

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(J.Hairline))
}

/** The taskbar/title-bar icon: a small reactor in the theme's colours, not Java's default cup. */
private class ReactorIcon(private val p: JarvisPalette) : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val r = size.minDimension / 2f
        drawCircle(p.background, r)
        drawCircle(Brush.radialGradient(listOf(Color.White, p.accent, Color.Transparent), center, r * 0.55f), r * 0.55f)
        drawCircle(p.accent, r * 0.78f, style = Stroke(r * 0.09f))
        drawArc(p.highlight, -60f, 120f, false, topLeft = center.copy(x = center.x - r * 0.93f, y = center.y - r * 0.93f), size = Size(r * 1.86f, r * 1.86f), style = Stroke(r * 0.1f))
        drawArc(p.highlight, 120f, 120f, false, topLeft = center.copy(x = center.x - r * 0.93f, y = center.y - r * 0.93f), size = Size(r * 1.86f, r * 1.86f), style = Stroke(r * 0.1f))
    }
}
