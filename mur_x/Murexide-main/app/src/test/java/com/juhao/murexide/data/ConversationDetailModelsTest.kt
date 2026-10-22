package com.juhao.murexide.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDetailModelsTest {
    @Test
    fun `cached mute state overrides stale detail state`() {
        val detail = ConversationDetail(
            chatId = "chat-1",
            chatType = 1,
            name = "Chat",
            avatarUrl = "",
            doNotDisturb = false
        )

        assertTrue(detail.withCachedMuteState(true).doNotDisturb)
        assertFalse(detail.copy(doNotDisturb = true).withCachedMuteState(false).doNotDisturb)
    }

    @Test
    fun `missing cached mute state keeps detail state`() {
        val detail = ConversationDetail(
            chatId = "chat-1",
            chatType = 1,
            name = "Chat",
            avatarUrl = "",
            doNotDisturb = true
        )

        assertTrue(detail.withCachedMuteState(null).doNotDisturb)
    }
}
