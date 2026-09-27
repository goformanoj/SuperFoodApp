package com.jarvis.os.desktop

import java.io.File

/**
 * Where the desktop app keeps its state — the desktop's stand-in for Android's
 * private app storage. `%APPDATA%\JarvisOS` on Windows, `~/.jarvis-os` elsewhere.
 *
 * Nothing here is shared with other users of the machine, and nothing here is a
 * build secret: it holds the conversation, remembered facts, and the anonymous
 * identity's refresh token (the same thing the phone keeps in SharedPreferences).
 */
object AppDirs {

    val root: File by lazy {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        val dir = if (appData != null) File(appData, "JarvisOS")
        else File(System.getProperty("user.home"), ".jarvis-os")
        dir.apply { mkdirs() }
    }

    fun file(name: String): File = File(root, name)
}
