package com.jarvis.os.desktop.knowledge

import com.jarvis.os.desktop.brain.Brain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adding a file to JARVIS's documents: text extracted off the UI thread, stored and
 * indexed in the brain on the caller's thread (the brain is single-threaded). A file
 * already added and unchanged since is reused, not stored twice.
 */
object Library {
    suspend fun import(brain: Brain, file: File, conversationId: String?): Brain.Document {
        val f = file.absoluteFile
        val existing = brain.documentFor(f.path, f.lastModified())
        val doc = existing ?: run {
            val ex = withContext(Dispatchers.IO) { DocText.extract(f) }
            val chunks = DocText.chunks(ex.pages)
            brain.addDocument(
                ex.name, ex.kind, ex.unit,
                ex.pages.map { it.number to it.text }, chunks.map { it.page to it.text },
                path = f.path, modified = f.lastModified(), sourceConversation = conversationId,
            ).also { brain.log("document", "Added document: ${it.name}", detail = f.path) }
        }
        conversationId?.let { brain.attachDocument(it, doc.id) }
        return doc
    }
}
