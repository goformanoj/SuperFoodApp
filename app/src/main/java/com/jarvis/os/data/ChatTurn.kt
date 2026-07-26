package com.jarvis.os.data

/** One turn in the conversation. [role] is [USER] or [ASSISTANT]. */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}
