package com.jarvis.os.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The conversation persists as JSON in SharedPreferences, so the round-trip and
 * the corrupt-data fallback need Robolectric to exercise real prefs + org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationStoreRobolectricTest {

    private lateinit var context: Context
    private lateinit var store: ConversationStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ConversationStore(context)
    }

    @Test
    fun `turns round-trip through save and load`() {
        store.save(listOf(ChatTurn(ChatTurn.USER, "hi"), ChatTurn(ChatTurn.ASSISTANT, "hello")))
        val loaded = ConversationStore(context).load()
        assertEquals(2, loaded.size)
        assertEquals("hi", loaded[0].content)
        assertEquals(ChatTurn.ASSISTANT, loaded[1].role)
    }

    @Test
    fun `a fresh store loads nothing`() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `clear empties the history`() {
        store.save(listOf(ChatTurn(ChatTurn.USER, "x")))
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `corrupt json falls back to empty instead of crashing`() {
        // The store keys its history under "history" in the "jarvis_chat" prefs.
        context.getSharedPreferences("jarvis_chat", Context.MODE_PRIVATE)
            .edit().putString("history", "{ this is not valid json").apply()
        assertTrue(ConversationStore(context).load().isEmpty())
    }
}
