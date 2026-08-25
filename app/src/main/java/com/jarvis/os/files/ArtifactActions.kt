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

    /**
     * `<<OPENFILE|title>>` — open a document JARVIS has already made.
     *
     * **The capability that did not exist**, and its absence produced a failure
     * worth writing down. Asked to open a PDF it had just created, the model had
     * no way to say so, so it did the only two things it could: it emitted
     * `<<FILE>>` again — generating a *fourth* copy of the same document — and
     * then tried to drive the Files app by hand with
     * `<<OPEN|Files>>` + `<<TAP|WhatsApp Chat Summary.pdf>>`.
     *
     * The tap found no control with that label, recovery took over, and it tapped
     * a search box labelled "invoice". From the user's side: they asked to open a
     * file and the assistant made a new one and then wandered off into another
     * app.
     *
     * None of that is a planning failure. **The model behaved reasonably given a
     * vocabulary with no word for the thing it was asked to do**, which is a
     * prompt-design bug rather than a model one. A file the app itself wrote,
     * whose path it knows, should never be reached by looking for its name on a
     * screen.
     */
    private val OPEN = Regex("""<<OPENFILE\|([^>\n]*)>{1,2}""", RegexOption.IGNORE_CASE)

    /**
     * The title the reply asked to open, or null.
     *
     * @return the reply with the marker removed, and the requested title.
     */
    fun parseOpen(reply: String): Pair<String, String?> {
        val match = OPEN.find(reply) ?: return reply to null
        val title = match.groupValues[1].trim().take(MAX_TITLE)
        val cleaned = reply.replace(OPEN, "").trim()
        return cleaned to title.ifBlank { null }
    }

    /**
     * Which stored document a spoken title means.
     *
     * Speech gives back a title that is *close*, never exact — "whatsapp chat
     * summary" for "WhatsApp Chat Summary", or just "the whatsapp one". So an
     * equality check would fail almost every time it mattered.
     *
     * Four passes, most confident first: exact ignoring case, then the stored
     * title containing what was asked, then what was asked containing the stored
     * title, then the best word overlap. Ties go to the **most recent**, because
     * a repeated title is the same document remade and the newest is the one they
     * just watched appear.
     */
    fun bestMatch(titles: List<String>, asked: String): Int? {
        if (titles.isEmpty()) return null
        val want = asked.trim().lowercase()
        if (want.isEmpty()) return titles.lastIndex

        val lower = titles.map { it.trim().lowercase() }
        lower.indexOfLast { it == want }.let { if (it >= 0) return it }
        lower.indexOfLast { it.contains(want) }.let { if (it >= 0) return it }
        lower.indexOfLast { want.contains(it) }.let { if (it >= 0) return it }

        val wanted = want.split(NON_WORD).filter { it.length > 2 }.toSet()
        if (wanted.isEmpty()) return null
        var best = -1
        var bestScore = 0
        lower.forEachIndexed { i, title ->
            val score = title.split(NON_WORD).count { it.length > 2 && it in wanted }
            // `>=` so a later document wins a tie: the same title made twice is
            // the same document remade, and the newest is the one they saw.
            if (score > 0 && score >= bestScore) {
                bestScore = score
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private val NON_WORD = Regex("[^a-z0-9]+")

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
