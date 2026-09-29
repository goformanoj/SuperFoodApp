package com.jarvis.os.desktop.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Play X on YouTube" (found live, 2026-09-29: the agent was guessing a specific video's
 * URL from memory — a plausible-looking id that didn't exist — and, separately, opening a
 * page is not the same as pressing play on it). Both are fixed by NOT routing this through
 * the model at all: YouTube's own search results page is fetched directly (no API key, no
 * quota — the same page a browser would show) and the first real result's id is read out
 * of it. A real id always points at something that exists, and it costs zero AI tokens —
 * asking Groq's heavyweight browser_search to do this instead, as the agent first tried,
 * is why a handful of "play a song" turns burned tens of thousands of tokens for what is,
 * underneath, one plain HTTP request.
 */
object YouTubeSearch {

    /** [startVideoId] is set for a playlist result: the video its own listing starts on,
     *  needed to make it start playing rather than just show the list (see [playlistUrl]). */
    data class Result(val id: String, val title: String?, val startVideoId: String? = null)

    private const val TIMEOUT_MS = 10_000

    /** The first real video YouTube's own search returns for [query], or null if it fails. */
    suspend fun firstVideo(query: String): Result? = fetch(query, playlist = false)?.let { parseFirstVideo(it) }

    /** The first real playlist YouTube's own search returns for [query], or null if it fails. */
    suspend fun firstPlaylist(query: String): Result? = fetch(query, playlist = true)?.let { parseFirstPlaylist(it) }

    /** A watch link that starts playing on its own, rather than sitting there until clicked. */
    fun videoUrl(id: String) = "https://www.youtube.com/watch?v=$id&autoplay=1"

    /**
     * A playlist's watch link: the SAME autoplay trick, queued to the whole list. Needs a
     * starting video id — the bare `/playlist?list=` page is just a listing, so opening
     * THAT alone has the exact "opened, but didn't play" problem this exists to fix
     * (found live, 2026-09-29). Falls back to the listing only when no video id is known.
     */
    fun playlistUrl(playlistId: String, firstVideoId: String? = null) =
        if (firstVideoId != null) "https://www.youtube.com/watch?v=$firstVideoId&list=$playlistId&autoplay=1"
        else "https://www.youtube.com/playlist?list=$playlistId"

    private suspend fun fetch(query: String, playlist: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$q" + if (playlist) "&sp=EgIQAw%253D%253D" else ""
            val conn = URL(url).openConnection() as HttpURLConnection
            // Without a browser-like UA, YouTube serves a near-empty noscript page.
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    // ── Pure parsing — tested against saved page fragments, no network in tests ──

    private val VIDEO_ID = Regex(""""videoId":"([A-Za-z0-9_-]{11})"""")
    private val PLAYLIST_ID = Regex(""""playlistId":"([A-Za-z0-9_-]{10,})"""")
    // A playlist result's own item entries carry BOTH ids right next to each other
    // ({"videoId":"x","playlistId":"y"} — confirmed against a real results page,
    // 2026-09-29), which is a far more reliable anchor than guessing at how close two
    // separately-found ids are: one match gives a video and ITS OWN playlist together.
    private val VIDEO_THEN_PLAYLIST = Regex(""""videoId":"([A-Za-z0-9_-]{11})","playlistId":"([A-Za-z0-9_-]{10,})"""")
    // Video results carry {"title":{"runs":[{"text":"..."}]}}; some renderers use
    // {"title":{"simpleText":"..."}}; playlist "lockup" renderers use
    // {"title":{"content":"..."}} instead — all three are a title, just shaped
    // differently (confirmed against a real results page, 2026-09-29).
    private val TITLE_RUN = Regex(""""title":\{(?:"runs":\[\{"text"|"simpleText"|"content"):"((?:[^"\\]|\\.)*)"""")

    /** The first result that is a real video (search results also contain channel/mix noise
     *  with the same field name, so this pairs each id with the nearest title as a sanity check
     *  and simply takes the first one — good enough, and never worse than a guess). */
    fun parseFirstVideo(html: String): Result? {
        val m = VIDEO_ID.find(html) ?: return null
        val title = nearestTitle(html, m.range.first)
        return Result(m.groupValues[1], title)
    }

    fun parseFirstPlaylist(html: String): Result? {
        val paired = VIDEO_THEN_PLAYLIST.find(html)
        val plain = if (paired == null) PLAYLIST_ID.find(html) else null
        val playlistId = paired?.groupValues?.get(2) ?: plain?.groupValues?.get(1) ?: return null
        val anchor = (paired ?: plain)!!.range.first
        val title = nearestTitle(html, anchor)
        return Result(playlistId, title, startVideoId = paired?.groupValues?.get(1))
    }

    /** The closest "title" field to the match, before or after — the id and its title sit in
     *  the same renderer block, but not always in the same order, so this is simple and
     *  reliable without a full JSON parse rather than assuming one direction. */
    private fun nearestTitle(html: String, index: Int): String? {
        val window = 1_500
        val before = TITLE_RUN.findAll(html.substring(maxOf(0, index - window), index)).lastOrNull()
        val after = TITLE_RUN.find(html, index)?.takeIf { it.range.first - index < window }
        val text = after?.groupValues?.get(1) ?: before?.groupValues?.get(1) ?: return null
        return unescape(text).ifBlank { null }
    }

    private fun unescape(s: String): String = s
        .replace("\\u0026", "&").replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
}
