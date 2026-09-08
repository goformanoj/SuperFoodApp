package com.jarvis.os.control

/**
 * A per-app pack fetched from the Worker (Part C2.2): the generic control labels for
 * one app, learned from real traces and served to every install. This is the shared,
 * updatable sibling of [ControlVocabulary]'s baked-in seeds — a new label reaches the
 * phone as a deploy, not a reinstall.
 *
 * Pure data; the fetch/parse/cache is [com.jarvis.os.ai.PackClient], the in-memory
 * registry is [PackStore], and both are unit-tested (the pure parts off-device).
 */
data class AppPack(
    val pkg: String,
    val version: Int,
    /** intent (search/cart/add/checkout) → literal labels, best first. */
    val controls: Map<String, List<String>>,
    /** When it was fetched, so a stale pack can be refreshed. */
    val fetchedAt: Long,
) {
    fun labelsFor(intent: String): List<String> = controls[intent].orEmpty()
}

/**
 * Holds the packs fetched so far, keyed by exact package id (lowercased). Populated
 * by [com.jarvis.os.ai.PackClient] after a fetch or a cache load, and consulted by
 * [ControlVocabulary]. Pure and synchronized; no Android, no I/O — so it verifies
 * off-device and a fetch failure simply leaves it empty (the seeds still apply).
 */
object PackStore {

    /** A pack older than this is worth refetching; the cached copy is used meanwhile. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    private val packs = HashMap<String, AppPack>()

    @Synchronized
    fun put(pack: AppPack) {
        packs[pack.pkg.lowercase()] = pack
    }

    @Synchronized
    fun get(pkg: String?): AppPack? {
        val key = pkg?.lowercase() ?: return null
        return packs[key]
    }

    /** Labels the pack knows for [intent] in [pkg], best first. Empty when none. */
    @Synchronized
    fun candidatesFor(pkg: String?, intent: String): List<String> =
        get(pkg)?.labelsFor(intent).orEmpty()

    @Synchronized
    fun clear() = packs.clear()

    /** True when there is no pack for [pkg], or the one held is past its TTL. */
    fun isStale(pkg: String?, now: Long): Boolean {
        val pack = get(pkg) ?: return true
        return now - pack.fetchedAt > TTL_MS
    }
}
