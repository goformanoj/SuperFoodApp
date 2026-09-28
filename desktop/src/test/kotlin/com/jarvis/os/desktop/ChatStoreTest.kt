package com.jarvis.os.desktop

import com.jarvis.os.data.ChatTurn
import com.jarvis.os.desktop.ChatStore.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun conv(id: String, updated: Long, vararg lines: String) = Conversation(
        id = id,
        title = "t-$id",
        turns = lines.mapIndexed { i, s -> ChatTurn(if (i % 2 == 0) ChatTurn.USER else ChatTurn.ASSISTANT, s) },
        updatedMs = updated,
    )

    @Test
    fun roundTripsConversationsAndFacts() {
        val store = ChatStore(tmp.root.resolve("chat.json"))
        val state = ChatStore.State(
            conversations = listOf(conv("b", 200, "hi \"there\"\nnew line", "Hello."), conv("a", 100, "q", "a")),
            facts = listOf("Likes tea"),
        )
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun newestConversationComesFirst() {
        val store = ChatStore(tmp.root.resolve("chat.json"))
        store.save(ChatStore.State(listOf(conv("old", 1, "x"), conv("new", 9, "y"))))
        assertEquals(listOf("new", "old"), store.load().conversations.map { it.id })
    }

    @Test
    fun emptyConversationsAreNotSaved() {
        val store = ChatStore(tmp.root.resolve("chat.json"))
        store.save(ChatStore.State(listOf(conv("empty", 5), conv("real", 1, "hi"))))
        assertEquals(listOf("real"), store.load().conversations.map { it.id })
    }

    @Test
    fun v1FileBecomesOneConversationTitledByItsFirstMessage() {
        val f = tmp.root.resolve("chat.json").apply {
            writeText("""{"turns":[{"role":"user","content":"Plan my morning"},{"role":"assistant","content":"Sure."}],"facts":["kept"]}""")
        }
        val state = ChatStore(f).load()
        assertEquals(1, state.conversations.size)
        assertEquals("Plan my morning", state.conversations[0].title)
        assertEquals(2, state.conversations[0].turns.size)
        assertEquals(listOf("kept"), state.facts)
    }

    @Test
    fun missingFileIsAnEmptyState() {
        assertEquals(ChatStore.State(), ChatStore(tmp.root.resolve("none.json")).load())
    }

    @Test
    fun corruptFileIsAnEmptyStateNotACrash() {
        val f = tmp.root.resolve("chat.json").apply { writeText("{not json") }
        assertEquals(ChatStore.State(), ChatStore(f).load())
    }

    @Test
    fun unknownRolesAndBlankFactsAreDropped() {
        val decoded = ChatStore(tmp.root.resolve("chat.json")).decode(
            """{"v":2,"conversations":[{"id":"x","title":"T","updated":1,"turns":[{"role":"system","content":"x"},{"role":"user","content":"y"}]}],"facts":["", "kept"]}""",
        )
        assertEquals(listOf(ChatTurn(ChatTurn.USER, "y")), decoded.conversations[0].turns)
        assertEquals(listOf("kept"), decoded.facts)
    }

    @Test
    fun conversationCountIsCapped() {
        val store = ChatStore(tmp.root.resolve("chat.json"))
        val many = (1..ChatStore.MAX_CONVERSATIONS + 5).map { conv("c$it", it.toLong(), "m") }
        store.save(ChatStore.State(many))
        val loaded = store.load().conversations
        assertEquals(ChatStore.MAX_CONVERSATIONS, loaded.size)
        assertTrue(loaded.none { it.id == "c1" })
    }
}
