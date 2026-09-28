package com.jarvis.os.desktop.agent

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI

/**
 * The real machine behind [ToolBox.Host]. Deliberately narrow:
 *  - URLs open in the default browser (http/https only — checked by the tool).
 *  - Apps open ONLY through their Start-menu shortcuts (what the user sees in Start),
 *    or a handful of built-in Windows tools — never an arbitrary executable path or a
 *    shell command, so a model can't be talked into running something unexpected.
 *  - The clipboard is read, never written.
 */
class WindowsHost : ToolBox.Host {

    private val builtIns = mapOf(
        "notepad" to "notepad.exe", "calculator" to "calc.exe", "calc" to "calc.exe",
        "paint" to "mspaint.exe", "file explorer" to "explorer.exe", "explorer" to "explorer.exe",
    )

    override fun openUrl(url: String): Boolean = runCatching {
        Desktop.getDesktop().browse(URI(url)); true
    }.getOrDefault(false)

    override fun openApp(name: String): String? {
        val key = name.trim().lowercase().removeSuffix(".exe")
        builtIns[key]?.let { exe ->
            return runCatching { ProcessBuilder(exe).start(); name.trim().replaceFirstChar { it.uppercase() } }.getOrNull()
        }
        val shortcut = findShortcut(key) ?: return null
        return runCatching { Desktop.getDesktop().open(shortcut); shortcut.nameWithoutExtension }.getOrNull()
    }

    override fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    /** Best Start-menu shortcut for [query]: exact name, then starts-with, then contains. */
    private fun findShortcut(query: String): File? {
        if (query.isBlank()) return null
        val roots = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
        ).filter { it.isDirectory }
        val all = roots.flatMap { root ->
            root.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.equals("lnk", true) }.toList()
        }.filterNot { it.nameWithoutExtension.contains("uninstall", ignoreCase = true) }
        return ShortcutMatch.best(query, all.map { it.nameWithoutExtension })?.let { name -> all.first { it.nameWithoutExtension == name } }
    }
}

/** Picking the right Start-menu entry for a spoken app name — pure, tested. */
object ShortcutMatch {
    fun best(query: String, names: List<String>): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return names.firstOrNull { it.lowercase() == q }
            ?: names.filter { it.lowercase().startsWith(q) }.minByOrNull { it.length }
            ?: names.filter { it.lowercase().contains(q) }.minByOrNull { it.length }
            ?: names.filter { n -> q.split(' ').all { w -> n.lowercase().contains(w) } }.minByOrNull { it.length }
    }
}
