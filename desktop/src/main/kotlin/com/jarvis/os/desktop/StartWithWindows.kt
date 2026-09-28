package com.jarvis.os.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Start with Windows" (AGENT_PLAN §6): a value under HKCU\…\Run (the user's own startup
 * list, the same place Settings → Apps → Startup shows and lets them switch off). JARVIS
 * then starts in the tray, so routines and reminders keep working after a reboot.
 *
 * Off by default (AGENT_PLAN §8 decision 3); only the user's switch in Settings turns it on.
 * Only offered for the installed JARVIS.exe: a development run has no stable program to
 * point at. `reg.exe` is called with an argument list, never through a shell.
 */
object StartWithWindows {

    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "JARVIS"

    /** The installed JARVIS.exe this process runs from, or null in a development run. */
    fun exe(): File? = ProcessHandle.current().info().command().orElse(null)
        ?.let { File(it) }?.takeIf { it.name.equals("JARVIS.exe", ignoreCase = true) && it.isFile }

    val available: Boolean get() = exe() != null

    /** What goes in the registry: the exe, quoted, starting hidden in the tray. Pure; tested. */
    fun command(exe: File): String = "\"${exe.absolutePath}\" $BACKGROUND_FLAG"

    const val BACKGROUND_FLAG = "--background"

    fun isOn(): Boolean = run("reg", "query", KEY, "/v", NAME) == 0

    /** Returns true if Windows accepted the change. */
    fun set(on: Boolean): Boolean {
        val exe = exe() ?: return false
        return if (on) run("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", command(exe), "/f") == 0
        else run("reg", "delete", KEY, "/v", NAME, "/f") == 0
    }

    private fun run(vararg cmd: String): Int = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 } else p.exitValue()
    }.getOrDefault(-1)
}
