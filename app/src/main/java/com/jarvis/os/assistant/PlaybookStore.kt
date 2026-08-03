package com.jarvis.os.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The routes JARVIS has learned, on this phone.
 *
 * Deliberately per-install and never uploaded: a route records which apps the
 * user has and how they ask for things, which is a description of their phone
 * and their habits.
 *
 * Least-recently-used eviction at the cap, because a route that has not been
 * needed in a hundred requests is not the one worth keeping.
 */
class PlaybookStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_playbook", Context.MODE_PRIVATE)

    fun routes(): List<Route> = read()

    /** The best route for [utterance], most-used first, or null. */
    fun find(utterance: String): Pair<Route, List<com.jarvis.os.control.ScreenStep>>? =
        read().sortedByDescending { it.uses }
            .firstNotNullOfOrNull { route ->
                Playbook.match(utterance, route)?.let { route to it }
            }

    /**
     * Records [route], merging with an existing one for the same template so a
     * repeated success raises its use count rather than adding a duplicate.
     */
    fun remember(route: Route, nowMillis: Long) {
        val existing = read().toMutableList()
        val at = existing.indexOfFirst { it.template == route.template }
        if (at >= 0) {
            val old = existing[at]
            existing[at] = old.copy(
                markers = route.markers,
                uses = old.uses + 1,
                lastUsedMillis = nowMillis,
            )
        } else {
            existing.add(route.copy(lastUsedMillis = nowMillis))
        }
        write(existing.sortedByDescending { it.lastUsedMillis }.take(MAX_ROUTES))
    }

    /** Bumps a route's counters when it is replayed, so eviction sees the use. */
    fun markUsed(template: String, nowMillis: Long) {
        val existing = read().toMutableList()
        val at = existing.indexOfFirst { it.template == template }
        if (at < 0) return
        existing[at] = existing[at].let { it.copy(uses = it.uses + 1, lastUsedMillis = nowMillis) }
        write(existing)
    }

    fun forget(template: String) {
        write(read().filterNot { it.template == template })
    }

    fun clear() = write(emptyList())

    private fun read(): List<Route> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]").orEmpty())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val template = o.optString("template")
            val markers = o.optString("markers")
            if (template.isBlank() || markers.isBlank()) return@mapNotNull null
            Route(template, markers, o.optInt("uses", 1), o.optLong("last", 0L))
        }
    } catch (e: Exception) {
        // A corrupt store must not take the assistant down with it.
        emptyList()
    }

    private fun write(items: List<Route>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("template", it.template)
                    .put("markers", it.markers)
                    .put("uses", it.uses)
                    .put("last", it.lastUsedMillis),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        /** Enough to cover a person's habits; small enough to scan on every turn. */
        const val MAX_ROUTES = 40
        private const val KEY = "routes"
    }
}
