package com.jarvis.os.files

/** What kind of file JARVIS was asked to make. */
enum class ArtifactKind(val id: String, val extension: String, val mime: String) {
    PDF("pdf", "pdf", "application/pdf"),
    NOTE("note", "md", "text/markdown"),
    ;

    companion object {
        fun fromId(id: String): ArtifactKind =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NOTE
    }
}

/** A file JARVIS wants to create, straight from its reply. */
data class ArtifactRequest(val kind: ArtifactKind, val title: String, val body: String)

/**
 * Parses the file-creation block out of a reply:
 *
 *   <<FILE|pdf|Meeting notes>>
 *   # Heading
 *   - point one
 *   <<ENDFILE>>
 *
 * A block marker, not the usual single-line one, because a document body spans
 * lines and the existing markers stop at a newline by construction.
 *
 * Pure Kotlin, so all of it is unit-tested without a device.
 */
object ArtifactActions {

    /** A title has to fit a file name and a list row. */
    const val MAX_TITLE = 80

    /** Guards against a runaway generation filling the user's storage. */
    const val MAX_BODY = 20_000

    private val BLOCK = Regex(
        """<<FILE\|([A-Za-z]+)\|([^>\n]*)>{1,2}\s*\n?(.*?)(?:<<ENDFILE>{0,2}|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Returns the reply with the blocks removed, plus the files to create. */
    fun parse(reply: String): Pair<String, List<ArtifactRequest>> {
        val requests = mutableListOf<ArtifactRequest>()
        var clean = reply
        for (match in BLOCK.findAll(reply)) {
            clean = clean.replace(match.value, "")
            val body = match.groupValues[3].trim()
            if (body.isEmpty()) continue
            requests.add(
                ArtifactRequest(
                    kind = ArtifactKind.fromId(match.groupValues[1]),
                    title = match.groupValues[2].trim().ifBlank { "Untitled" }.take(MAX_TITLE),
                    body = body.take(MAX_BODY),
                ),
            )
        }
        return clean.trim() to requests
    }

    /**
     * A file name that is safe on any filesystem and still recognisable. Falls
     * back rather than producing an empty or hidden name.
     */
    fun fileName(title: String, kind: ArtifactKind, stamp: String): String {
        val safe = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "artifact" }
        return "$safe-$stamp.${kind.extension}"
    }
}
