package com.juhao.murexide.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRepositoryRecallTest {
    @Test
    fun `single recall uses one repeated message id`() {
        val request = createRecallMessageRequest(
            msgId = "message-id",
            chatId = "group-id",
            chatType = 2
        )

        assertEquals(listOf("message-id"), request.msg_id)
        assertEquals("group-id", request.chat_id)
        assertEquals(2L, request.chat_type)
    }
}
