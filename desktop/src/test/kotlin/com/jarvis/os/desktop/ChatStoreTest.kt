package com.jarvis.os.desktop

import com.jarvis.os.data.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTripsTurnsAndFacts() {
        val store = ChatStore(tmp.root.resolve("chat.json"))
        val state = ChatStore.State(
            turns = listOf(ChatTurn(ChatTurn.USER, "hi \"there\"\nnew line"), ChatTurn(ChatTurn.ASSISTANT, "Hello.")),
            facts = listOf("Likes tea"),
        )
        store.save(state)
        assertEquals(state, store.load())
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
        val store = ChatStore(tmp.root.resolve("chat.json"))
        val decoded = store.decode(
            """{"turns":[{"role":"system","content":"x"},{"role":"user","content":"y"}],"facts":["", "kept"]}""",
        )
        assertEquals(listOf(ChatTurn(ChatTurn.USER, "y")), decoded.turns)
        assertEquals(listOf("kept"), decoded.facts)
    }

    @Test
    fun overwritingKeepsOnlyTheLatest() {
        val store = ChatStore(tmp.root.resolve("chat.json"))
        store.save(ChatStore.State(facts = listOf("old")))
        store.save(ChatStore.State(facts = listOf("new")))
        assertEquals(listOf("new"), store.load().facts)
    }
}
