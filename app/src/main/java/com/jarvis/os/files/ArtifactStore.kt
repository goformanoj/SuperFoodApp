package com.jarvis.os.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file JARVIS made, as listed in the Files tab. */
data class Artifact(
    val fileName: String,
    val title: String,
    val kind: ArtifactKind,
    val createdMillis: Long,
    val sizeBytes: Long,
)

/**
 * Where JARVIS's artifacts live.
 *
 * Deliberately app-private (`filesDir/artifacts`) and shared out through a
 * FileProvider: that needs no storage permission at all, so the feature adds
 * nothing for a Play reviewer to question and nothing to the Data safety form.
 * The files never leave the device unless the user shares one.
 */
class ArtifactStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "artifacts").apply { mkdirs() }
    private val index = File(dir, "index.json")

    fun directory(): File = dir

    fun fileFor(artifact: Artifact): File = File(dir, artifact.fileName)

    /** Newest first — the thing just made is the thing most likely wanted. */
    fun list(): List<Artifact> = read()
        .filter { File(dir, it.fileName).exists() }
        .sortedByDescending { it.createdMillis }

    fun add(artifact: Artifact) {
        write(read().filterNot { it.fileName == artifact.fileName } + artifact)
    }

    fun delete(artifact: Artifact) {
        File(dir, artifact.fileName).delete()
        write(read().filterNot { it.fileName == artifact.fileName })
    }

    private fun read(): List<Artifact> = try {
        if (!index.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Artifact(
                    fileName = o.optString("file"),
                    title = o.optString("title"),
                    kind = ArtifactKind.fromId(o.optString("kind")),
                    createdMillis = o.optLong("created"),
                    sizeBytes = o.optLong("size"),
                )
            }.filter { it.fileName.isNotBlank() }
        }
    } catch (e: Exception) {
        // A corrupt index must not lose the files themselves, and must not crash
        // the screen that lists them.
        emptyList()
    }

    private fun write(items: List<Artifact>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("file", it.fileName)
                    .put("title", it.title)
                    .put("kind", it.kind.id)
                    .put("created", it.createdMillis)
                    .put("size", it.sizeBytes),
            )
        }
        runCatching { index.writeText(arr.toString()) }
    }
}
