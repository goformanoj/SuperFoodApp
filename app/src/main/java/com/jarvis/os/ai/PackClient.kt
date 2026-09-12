package com.jarvis.os.ai

import android.content.Context
import com.jarvis.os.BuildConfig
import com.jarvis.os.control.AppPack
import com.jarvis.os.control.PackStore
import com.jarvis.os.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

/**
 * Fetches and caches per-app packs from the Worker (Part C2.2).
 *
 * When JARVIS sees an app come to the front, [ensure] warms that app's pack: it
 * returns immediately if a fresh copy is already in [PackStore], otherwise it fetches
 * `GET <WORKER_URL>/apps/<package>` in the background, stores the result, and writes a
 * disk copy so the next launch starts warm. [init] loads those disk copies at startup.
 *
 * Deliberately best-effort and silent on failure: a pack only ever *adds* a label to
 * try after the requested one missed, so no pack simply means the baked-in
 * [com.jarvis.os.control.ControlVocabulary] seeds apply, exactly as before this
 * existed. Mirrors [ProxyClient]'s HTTP shape and sends the same shared app secret.
 *
 * The pure pack parsing is [parse], tested; the fetch/cache is exercised in CI.
 */
object PackClient {

    private val WORKER_URL = BuildConfig.WORKER_URL.trimEnd('/')
    private const val CACHE_DIR = "packs"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var appContext: Context? = null

    fun isConfigured(): Boolean =
        WORKER_URL.isNotBlank() && BuildConfig.PROXY_SECRET.isNotBlank()

    /** Loads any cached packs into [PackStore]. Call once at startup. */
    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { loadCache() }
    }

    /**
     * Ensure a fresh pack for [pkg] is available, fetching in the background if the
     * held copy is missing or stale. Cheap and idempotent — returns at once when the
     * pack is fresh or a fetch for it is already in flight.
     */
    fun ensure(pkg: String?) {
        if (!isConfigured()) return
        val app = pkg?.trim().orEmpty()
        if (app.isEmpty()) return
        if (!PackStore.isStale(app, System.currentTimeMillis())) return
        if (!inFlight.add(app.lowercase())) return
        scope.launch {
            try {
                fetch(app)
            } finally {
                inFlight.remove(app.lowercase())
            }
        }
    }

    private fun fetch(pkg: String) {
        val endpoint = "$WORKER_URL/apps/${URLEncoder.encode(pkg, "UTF-8")}"
        val conn = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return
        }
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("X-Proxy-Secret", BuildConfig.PROXY_SECRET)
        try {
            val code = conn.responseCode
            if (code == 404) return // no pack for this app — expected, not an error
            if (code !in 200..299) return
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val pack = parse(body, System.currentTimeMillis()) ?: return
            PackStore.put(pack)
            writeCache(pkg, body)
            DebugLog.log(DebugLog.Stage.SCREEN, "pack loaded for $pkg (${pack.controls.size} controls)")
        } catch (e: Exception) {
            // Being offline is the common case, not a fault: pack warming is
            // best-effort and the baked-in vocabulary still applies. Logging every
            // UnknownHost/Connect/Timeout floods the trace (a device log showed
            // dozens in a row while the phone had no network), so those are silent;
            // only a genuinely unexpected failure gets a line.
            if (e !is java.net.UnknownHostException &&
                e !is java.net.ConnectException &&
                e !is java.net.SocketTimeoutException
            ) {
                DebugLog.log(DebugLog.Stage.ERROR, "pack fetch failed for $pkg: ${e.javaClass.simpleName}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Parses a served pack. Returns null for anything malformed. */
    internal fun parse(json: String, fetchedAt: Long): AppPack? {
        return try {
            val o = JSONObject(json)
            val pkg = o.optString("package")
            if (pkg.isBlank()) return null
            val version = o.optInt("version", 1)
            val controlsObj = o.optJSONObject("controls") ?: JSONObject()
            val controls = HashMap<String, List<String>>()
            val keys = controlsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = controlsObj.optJSONArray(key) ?: continue
                val labels = (0 until arr.length())
                    .map { arr.optString(it) }
                    .filter { it.isNotBlank() }
                if (labels.isNotEmpty()) controls[key] = labels
            }
            AppPack(pkg, version, controls, fetchedAt)
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheDir(): File? {
        val dir = appContext?.filesDir ?: return null
        return File(dir, CACHE_DIR).apply { if (!exists()) mkdirs() }
    }

    private fun fileFor(pkg: String): File? =
        cacheDir()?.let { File(it, pkg.lowercase().replace(Regex("[^a-z0-9._-]"), "_") + ".json") }

    private fun writeCache(pkg: String, json: String) {
        runCatching { fileFor(pkg)?.writeText(json) }
    }

    private fun loadCache() {
        val dir = cacheDir() ?: return
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in files) {
            if (!f.isFile) continue
            // Use the file's own mtime as the fetch time, so TTL survives a restart.
            runCatching {
                parse(f.readText(), f.lastModified())?.let { PackStore.put(it) }
            }
        }
    }
}
