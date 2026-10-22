package com.juhao.murexide.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBubbleSenderNameTest {
    @Test
    fun `whitespace-only sender name is preserved`() {
        val senderName = "   "

        assertEquals(senderName, resolveSenderDisplayName(senderName, isMine = false))
    }

    @Test
    fun `empty sender name still uses the existing fallback`() {
        assertEquals("原发送者", resolveSenderDisplayName("", isMine = false))
        assertEquals("我", resolveSenderDisplayName("", isMine = true))
    }
}
