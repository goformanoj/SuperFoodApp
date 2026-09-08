package com.jarvis.os.debug

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A trace of what JARVIS actually did, so a failure can be read instead of
 * guessed at. Every turn records what was heard, what the model replied, which
 * markers were parsed, which actions ran, and what was spoken. The user exports
 * it from the Diagnostics screen.
 *
 * ## Memory + disk
 *
 * The last [MAX_ENTRIES] entries are held in memory for the Diagnostics view.
 * Once [attach] has supplied a directory, every entry is ALSO appended to a
 * newline-delimited file, so a trace is no longer thrown away on restart — the
 * labelled `HEARD → REPLY → MARKS → SCREEN` examples (with their outcome) are
 * exactly the corpus the app-learning packs (Part C2) and any on-device tuning
 * (Part H) need, and you cannot learn from data you deleted. The file keeps up
 * to [MAX_DISK_ENTRIES], compacting the oldest away once it grows past that. On
 * [attach] the most recent entries are loaded back so history survives a restart.
 *
 * Every entry is passed through [redact] BEFORE it is stored or written, so an
 * API key can never travel out in a shared log — a safety property, not a nicety.
 * All disk work is best-effort: a diagnostic must never crash the app, so every
 * file operation swallows its own failure and the in-memory log carries on.
 */
object DebugLog {

    const val MAX_ENTRIES = 300

    /** How many entries the on-disk trace keeps before the oldest are compacted away. */
    const val MAX_DISK_ENTRIES = 2000

    /** Compaction runs only once the file has drifted this far past the cap, to amortise the rewrite. */
    private const val COMPACT_SLACK = 200

    private const val FILE_NAME = "debug-trace.log"

    enum class Stage(val label: String) {
        HEARD("HEARD"),
        THINK("THINK"),
        REPLY("REPLY"),
        MARKERS("MARKS"),
        CALENDAR("CAL"),
        SCREEN("SCREEN"),
        SPOKE("SPOKE"),
        SESSION("SESS"),
        ALARM("ALARM"),
        FILE("FILE"),
        ERROR("ERROR"),
    }

    data class Entry(val timeMillis: Long, val stage: Stage, val detail: String)

    private val entries = ArrayDeque<Entry>()

    /** The backing file once [attach] has run; null keeps behaviour purely in-memory. */
    private var file: File? = null

    /** Valid records currently on disk, tracked so compaction is a rare rewrite rather than a per-log scan. */
    private var diskCount = 0

    @Synchronized
    fun log(stage: Stage, detail: String) {
        val entry = Entry(System.currentTimeMillis(), stage, redact(detail))
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        append(entry)
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        file?.let { f -> runCatching { if (f.exists()) f.writeText("") } }
        diskCount = 0
    }

    /**
     * Point the log at a directory (the app's `filesDir`) and load the most recent
     * persisted entries back into memory. Called once at startup. Safe to call with
     * a fresh or missing file; any read failure just starts empty.
     */
    @Synchronized
    fun attach(dir: File) {
        val f = File(dir, FILE_NAME)
        file = f
        entries.clear()
        diskCount = 0
        runCatching {
            if (f.exists()) {
                val decoded = f.readLines().mapNotNull { decode(it) }
                diskCount = decoded.size
                decoded.takeLast(MAX_ENTRIES).forEach { entries.addLast(it) }
            }
        }
    }

    /** Detach the backing file (used by tests). The in-memory log is left untouched. */
    @Synchronized
    fun detach() {
        file = null
        diskCount = 0
    }

    /** Appends one already-redacted entry to disk, compacting if the file has grown too far. */
    private fun append(entry: Entry) {
        val f = file ?: return
        runCatching {
            f.appendText(encode(entry) + "\n")
            diskCount++
            if (diskCount > MAX_DISK_ENTRIES + COMPACT_SLACK) compact(f)
        }
    }

    /** Rewrites the file keeping only the newest [MAX_DISK_ENTRIES] valid records. */
    private fun compact(f: File) {
        runCatching {
            val kept = f.readLines().mapNotNull { decode(it) }.takeLast(MAX_DISK_ENTRIES)
            f.writeText(kept.joinToString("") { encode(it) + "\n" })
            diskCount = kept.size
        }
    }

    /**
     * One entry as a single line: `timeMillis \t STAGE \t escapedDetail`. The detail
     * is escaped so a multi-line screen render (or a stray tab) cannot break the
     * record boundary or the field split. Round-trips through [decode].
     */
    fun encode(entry: Entry): String =
        "${entry.timeMillis}\t${entry.stage.name}\t${escape(entry.detail)}"

    /**
     * Parses one line written by [encode]. Returns null for anything malformed — a
     * partially-written last line, an unknown stage from an older/newer build — so a
     * single bad record never aborts loading the rest of the trace.
     */
    fun decode(line: String): Entry? {
        if (line.isEmpty()) return null
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val stage = runCatching { Stage.valueOf(parts[1]) }.getOrNull() ?: return null
        return Entry(time, stage, unescape(parts[2]))
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Strips anything shaped like an API key. The log is meant to be shared, and
     * a stack trace or an error body from a provider can echo the key back.
     */
    fun redact(text: String): String = text
        .replace(GROQ_KEY, REDACTED)
        .replace(GOOGLE_KEY, REDACTED)
        .replace(BEARER, "Bearer $REDACTED")

    /** The whole log as shareable plain text, newest last. */
    fun export(header: String = ""): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val body = snapshot().joinToString("\n") { entry ->
            "${clock.format(Date(entry.timeMillis))}  ${entry.stage.label.padEnd(6)}  ${entry.detail}"
        }
        return if (header.isBlank()) body else "$header\n\n$body"
    }

    private const val REDACTED = "***redacted***"
    private val GROQ_KEY = Regex("""gsk_[A-Za-z0-9]{8,}""")
    private val GOOGLE_KEY = Regex("""AIza[A-Za-z0-9_\-]{20,}""")
    private val BEARER = Regex("""Bearer\s+[A-Za-z0-9_\-.]{12,}""")
}
