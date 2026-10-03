package com.jarvis.os.desktop.knowledge

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "What's in my SuperFoodApp folder?" — a plain look inside one folder, and a bounded search for a folder
 * by name. Windows Search (see [FileSearch]) deliberately leaves out code and returns files, never folders,
 * so on its own it can't answer this.
 *
 * Read-only and capped: a listing is names, sizes and dates, never file contents, and anything that looks
 * like a secret (keys, .env files, local.properties) is left out and only counted.
 */
object FolderList {

    data class Entry(val name: String, val isDir: Boolean, val size: Long?, val modified: String?)
    data class Listing(val path: String, val entries: List<Entry>, val total: Int, val hidden: Int)

    /** Entries shown in one listing; the model is told how many more there are. */
    const val MAX_ENTRIES = 60

    private val PRIVATE = Regex(
        """(?i)^(\.env.*|.*\.(pem|key|pfx|p12|jks|keystore)|id_rsa.*|id_ed25519.*|local\.properties|gradle\.properties|secrets?\..*|credentials.*|\.npmrc|\.netrc)$""",
    )

    /** Places the user never means and that make a search crawl. */
    private val NOISE_DIRS = setOf(
        "node_modules", ".git", ".gradle", ".idea", "appdata", "windows", "program files", "program files (x86)",
        "programdata", "\$recycle.bin", "system volume information", "build",
    )

    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    /** The folder's contents, folders first then files A–Z. Null if it can't be opened. */
    fun list(dir: File, max: Int = MAX_ENTRIES): Listing? {
        val all = dir.listFiles() ?: return null
        val visible = all.filter { f -> !PRIVATE.matches(f.name) && !runCatching { Files.isHidden(f.toPath()) }.getOrDefault(false) }
        val sorted = visible.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val entries = sorted.take(max).map { f ->
            Entry(
                f.name, f.isDirectory, if (f.isFile) f.length() else null,
                runCatching { DAY.format(Instant.ofEpochMilli(f.lastModified())) }.getOrNull(),
            )
        }
        return Listing(dir.path, entries, total = visible.size, hidden = all.size - visible.size)
    }

    /** "Super Food App", "superfoodapp" and "SuperFoodApp" are the same folder. */
    fun normalise(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Folders called [name] under [roots], nearest first. Breadth-first, [maxDepth] levels, stops at
     * [limitMs]; never descends into the noisy places above. Returns up to [limit] matches.
     */
    fun find(
        name: String, roots: List<File>, maxDepth: Int = 4, limitMs: Long = 4_000, limit: Int = 3,
        clock: () -> Long = System::nanoTime,
    ): List<File> {
        val want = normalise(name)
        if (want.isEmpty()) return emptyList()
        val deadline = clock() + limitMs * 1_000_000
        val found = ArrayList<File>()
        var level = roots.filter { it.isDirectory }
        var depth = 0
        while (level.isNotEmpty() && depth <= maxDepth && found.size < limit) {
            val next = ArrayList<File>()
            for (dir in level) {
                if (clock() > deadline) return found
                for (child in dir.listFiles().orEmpty()) {
                    if (!child.isDirectory || child.name.lowercase() in NOISE_DIRS) continue
                    if (normalise(child.name) == want) { found += child; if (found.size >= limit) return found }
                    else next += child
                }
            }
            level = next
            depth++
        }
        return found
    }
}
