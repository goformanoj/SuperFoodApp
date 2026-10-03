package com.jarvis.os.desktop.agent

import com.jarvis.os.desktop.knowledge.FileSearch
import com.jarvis.os.desktop.knowledge.KnowledgeClient
import com.jarvis.os.desktop.knowledge.YouTubeSearch
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
 *  - Files open with their default app, and only documents (ToolBox refuses programs and
 *    scripts before this is ever called).
 *  - The screen is captured only through [captureScreen], which the app wires to hide its
 *    own window for the moment of the shot.
 */
class WindowsHost(
    /** The grounding line for web searches (the current date/time), so "latest" means now. */
    private val context: () -> String = { "" },
    /** One JPEG of the screen the user is on. */
    private val captureScreen: (suspend () -> ByteArray)? = null,
) : ToolBox.Host {

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

    override fun openFile(path: String): Boolean = runCatching {
        Desktop.getDesktop().open(File(path)); true
    }.getOrDefault(false)

    override suspend fun webSearch(query: String) = KnowledgeClient.webSearch(query, context())

    override suspend fun searchFiles(q: FileSearch.Query) = FileSearch.run(q)

    /** Where a folder named in words ("my SuperFoodApp folder") is looked for: the user's home, then every drive. */
    override fun folderRoots(): List<File> =
        listOf(File(System.getProperty("user.home"))) + File.listRoots().filter { it.isDirectory }

    override suspend fun youtubeVideo(query: String) = YouTubeSearch.firstVideo(query)
    override suspend fun youtubePlaylist(query: String) = YouTubeSearch.firstPlaylist(query)

    override suspend fun askAboutScreen(question: String): String {
        val shot = captureScreen ?: throw UnsupportedOperationException("Looking at the screen isn't available here.")
        return KnowledgeClient.askAboutImage(shot(), question, context())
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
