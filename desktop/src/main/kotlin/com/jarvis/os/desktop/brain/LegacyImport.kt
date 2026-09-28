package com.jarvis.os.desktop.brain

import com.jarvis.os.desktop.ChatStore
import java.io.File

/**
 * Moves the desktop's old flat storage (`chat.json`: conversations + a list of fact
 * strings) into the brain — once, losslessly, in order. The old file is kept, renamed
 * `chat.json.imported`, as a backup; nothing is deleted.
 *
 * Runs only when the brain is empty, so starting the app twice can never duplicate
 * anything. Tested in BrainTest.
 */
object LegacyImport {

    data class Result(val conversations: Int, val messages: Int, val memories: Int)

    fun run(brain: Brain, legacyFile: File): Result? {
        if (!legacyFile.exists() || !brain.isEmpty()) return null
        val state = ChatStore(legacyFile).load()
        val result = import(brain, state)
        legacyFile.renameTo(File(legacyFile.parentFile, legacyFile.name + ".imported"))
        return result
    }

    fun import(brain: Brain, state: ChatStore.State): Result {
        var messages = 0
        // Oldest first, so relative order (and "most recent" in the sidebar) survives.
        state.conversations.sortedBy { it.updatedMs }.forEach { c ->
            brain.createConversation(c.title, id = c.id)
            // Old turns had no timestamps; spread them just before the conversation's
            // last-updated time so their order is kept exactly.
            val n = c.turns.size
            c.turns.forEachIndexed { i, t ->
                brain.addMessage(c.id, t.role, t.content, at = c.updatedMs - (n - 1 - i))
                messages++
            }
        }
        val memories = state.facts.count { brain.remember(it) != null }
        if (state.conversations.isNotEmpty() || memories > 0) {
            brain.log("import", "Moved ${state.conversations.size} chats and $memories memories into the new brain")
        }
        return Result(state.conversations.size, messages, memories)
    }
}
