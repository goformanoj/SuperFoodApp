package com.jarvis.os.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The Quick bar's key, anywhere in Windows (AGENT_PLAN §6): Win32 RegisterHotKey on a
 * thread of its own with a message loop, the way Windows expects. Only this one key
 * combination is ever seen: nothing else typed anywhere reaches JARVIS (this is not a
 * keyboard hook).
 *
 * Alt+Space first (the plan's choice, the same key PowerToys Run uses); if another app
 * already holds it, Ctrl+Alt+J. [start] reports which one it got, so Settings can say.
 */
object GlobalHotkey {

    data class Combo(val mods: Int, val vk: Int, val label: String)

    private const val MOD_ALT = 0x0001
    private const val MOD_CONTROL = 0x0002
    private const val MOD_NOREPEAT = 0x4000
    private const val HOTKEY_ID = 0x4A41   // "JA"

    val CANDIDATES = listOf(
        Combo(MOD_ALT, 0x20, "Alt+Space"),
        Combo(MOD_CONTROL or MOD_ALT, 0x4A, "Ctrl+Alt+J"),
    )

    @Volatile private var loopThreadId = 0

    /** Registers the first free combination and calls [onPress] (on the hotkey thread) each time. Null if none could be had. */
    fun start(onPress: () -> Unit): String? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        stop()
        val got = CompletableFuture<String?>()
        thread(isDaemon = true, name = "jarvis-hotkey") {
            try {
                val u = User32.INSTANCE
                val combo = CANDIDATES.firstOrNull { u.RegisterHotKey(null, HOTKEY_ID, it.mods or MOD_NOREPEAT, it.vk) }
                if (combo == null) { got.complete(null); return@thread }
                loopThreadId = Kernel32.INSTANCE.GetCurrentThreadId()
                got.complete(combo.label)
                val msg = WinUser.MSG()
                while (u.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WinUser.WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) runCatching(onPress)
                }
                u.UnregisterHotKey(null, HOTKEY_ID)
            } catch (e: Throwable) {
                got.complete(null)
            } finally {
                loopThreadId = 0
            }
        }
        return runCatching { got.get(3, TimeUnit.SECONDS) }.getOrNull()
    }

    /** Ends the message loop, which unregisters the key. */
    fun stop() {
        val id = loopThreadId
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        loopThreadId = 0
    }
}
