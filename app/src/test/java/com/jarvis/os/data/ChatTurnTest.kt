package com.jarvis.os.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTurnTest {

    @Test
    fun `role constants match what the model APIs expect`() {
        assertEquals("user", ChatTurn.USER)
        assertEquals("assistant", ChatTurn.ASSISTANT)
    }

    @Test
    fun `a turn keeps its role and content`() {
        val turn = ChatTurn(ChatTurn.ASSISTANT, "On it.")
        assertEquals("assistant", turn.role)
        assertEquals("On it.", turn.content)
    }
}
