package com.jarvis.os.desktop.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * "Find the invoice from March" (AGENT_PLAN §5, S5) — through the Windows Search index
 * that every Windows PC already keeps (names AND contents of the user's files). JARVIS
 * indexes nothing itself and copies nothing: it asks Windows, and gets paths back.
 *
 * The query is built HERE from sanitised words, never from model text pasted into SQL:
 * each word is reduced to letters, digits and . - _, so nothing the model says can change
 * the query's shape. The SQL goes to PowerShell's ADODB (Search.CollatorDSO) through an
 * environment variable, not the command line, so no shell quoting is involved either.
 */
object FileSearch {

    data class Query(val words: List<String>, val kind: String? = null, val extension: String? = null, val after: LocalDate? = null, val before: LocalDate? = null)
    data class Found(val path: String, val modified: String?, val size: Long?) {
        val name: String get() = path.substringAfterLast('\\').substringAfterLast('/')
    }

    /** Windows' own file categories (System.Kind). */
    val KINDS = setOf("document", "picture", "music", "video", "email", "program")

    /** Model arguments → a safe query, or null with nothing to search for. Pure; tested. */
    fun query(text: String, kind: String? = null, after: String? = null, before: String? = null): Query? {
        val raw = Regex("[\\p{L}\\p{N}._-]+").findAll(text).map { it.value.trim('.', '-', '_') }.filter { it.length >= 2 }.toList()
        // "pdf" / ".xlsx" in the query is a file type, not a word to find inside files.
        val ext = raw.firstOrNull { it.lowercase() in EXTENSIONS }?.lowercase()
        val words = raw.filterNot { it.lowercase() in EXTENSIONS || it.lowercase() in FILLER }.map { it.take(40) }.distinct().take(6)
        val k = kind?.trim()?.lowercase()?.takeIf { it in KINDS }
        if (words.isEmpty() && ext == null) return null
        return Query(words, k, ext, date(after), date(before))
    }

    /** The Windows Search SQL for [q]. Pure; tested. Every value in it has been sanitised by [query]. */
    fun sql(q: Query, limit: Int = 25): String {
        val where = mutableListOf("SCOPE='file:'", "System.ItemType <> 'Directory'")
        if (q.words.isNotEmpty()) {
            val contains = q.words.joinToString(" AND ") { "\"$it*\"" }
            val names = q.words.joinToString(" AND ") { "System.FileName LIKE '%$it%'" }
            where += "(CONTAINS(*,'$contains') OR ($names))"
        }
        q.extension?.let { where += "System.FileExtension = '.$it'" }
        // "The invoice" means a document, not the web page source that mentions invoices
        // (live: 24 of 25 hits were .js/.css). Code is left out unless a type was asked for.
        if (q.extension == null) CODE_EXTENSIONS.forEach { where += "System.FileExtension <> '.$it'" }
        q.kind?.let { where += "System.Kind = '$it'" }
        q.after?.let { where += "System.DateModified >= '$it 00:00:00'" }
        q.before?.let { where += "System.DateModified < '${it.plusDays(1)} 00:00:00'" }
        return "SELECT TOP $limit System.ItemPathDisplay, System.DateModified, System.Size FROM SYSTEMINDEX WHERE " +
            where.joinToString(" AND ") + " ORDER BY System.DateModified DESC"
    }

    /** Lines of `path<TAB>yyyy-MM-ddTHH:mm<TAB>size` → results, app-internal folders dropped. Pure; tested. */
    fun parse(output: String): List<Found> = output.lineSequence().mapNotNull { line ->
        val cols = line.trimEnd('\r').split('\t')
        val path = cols.getOrNull(0)?.trim().orEmpty()
        if (path.isEmpty() || !(path.contains(":\\") || path.startsWith("\\\\"))) return@mapNotNull null
        if (NOISE.any { path.contains(it, ignoreCase = true) }) return@mapNotNull null
        Found(path, cols.getOrNull(1)?.takeIf { it.isNotBlank() }, cols.getOrNull(2)?.trim()?.toLongOrNull())
    }.distinctBy { it.path.lowercase() }.toList()

    /** Runs [q] against the Windows index. Throws with a sentence fit for the user. */
    suspend fun run(q: Query): List<Found> = withContext(Dispatchers.IO) {
        val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(StandardCharsets.UTF_16LE))
        val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.environment()["JARVIS_SQL"] = sql(q)
        val p = pb.start()
        val out = p.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (!p.waitFor(25, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IllegalStateException("Windows Search took too long to answer.") }
        if (out.contains("JARVIS_SEARCH_UNAVAILABLE")) throw IllegalStateException("Windows Search isn't available on this laptop (the indexing service may be off).")
        parse(out)
    }

    private fun date(s: String?): LocalDate? = s?.trim()?.takeIf { it.length >= 10 }?.let {
        try { LocalDate.parse(it.take(10)) } catch (e: DateTimeParseException) { null }
    }

    private val CODE_EXTENSIONS = listOf(
        "js", "mjs", "cjs", "ts", "tsx", "jsx", "css", "scss", "map", "json", "kt", "kts", "java", "class", "py", "pyc",
        "c", "h", "cpp", "cs", "go", "rs", "rb", "php", "lock", "dll", "sys", "tmp", "log", "ini", "xml", "yml", "yaml",
    )
    private val EXTENSIONS = setOf("pdf", "docx", "doc", "xlsx", "xls", "csv", "pptx", "ppt", "txt", "md", "jpg", "jpeg", "png", "mp4", "mp3", "zip")
    private val FILLER = setOf("file", "files", "the", "my", "from", "in", "on", "of", "and", "or", "for", "with", "find", "a", "an")
    /** App-internal and system places that the index covers but the user never means. */
    private val NOISE = listOf("\\AppData\\", "\\\$Recycle.Bin\\", "\\ProgramData\\", "\\Windows\\", "\\node_modules\\", "\\.git\\", "\\.gradle\\")

    private val SCRIPT = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        try {
          ${'$'}c = New-Object -ComObject ADODB.Connection
          ${'$'}c.Open("Provider=Search.CollatorDSO;Extended Properties='Application=Windows';")
          ${'$'}r = New-Object -ComObject ADODB.Recordset
          ${'$'}r.Open(${'$'}env:JARVIS_SQL, ${'$'}c)
        } catch { 'JARVIS_SEARCH_UNAVAILABLE'; exit 0 }
        while (-not ${'$'}r.EOF) {
          ${'$'}p = ${'$'}r.Fields.Item('System.ItemPathDisplay').Value
          ${'$'}d = ${'$'}r.Fields.Item('System.DateModified').Value
          ${'$'}s = ${'$'}r.Fields.Item('System.Size').Value
          ${'$'}ds = if (${'$'}d) { ([datetime]${'$'}d).ToLocalTime().ToString('yyyy-MM-ddTHH:mm') } else { '' }
          "${'$'}p`t${'$'}ds`t${'$'}s"
          ${'$'}r.MoveNext()
        }
        ${'$'}r.Close(); ${'$'}c.Close()
    """.trimIndent()
}
