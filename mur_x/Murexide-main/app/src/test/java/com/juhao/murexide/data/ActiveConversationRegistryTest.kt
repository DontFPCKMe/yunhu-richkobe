package com.juhao.murexide.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveConversationRegistryTest {
    @Before
    fun setUp() {
        ActiveConversationRegistry.resetForTests()
    }

    @After
    fun tearDown() {
        ActiveConversationRegistry.resetForTests()
    }

    @Test
    fun `incoming message in a resumed conversation does not increment unread`() {
        val owner = Any()
        ActiveConversationRegistry.activate(owner, ConversationKey("group", 2))

        assertFalse(
            ActiveConversationRegistry.shouldIncrementUnread(
                incomingMessage(chatId = "group", chatType = 2, senderId = "member")
            )
        )

        ActiveConversationRegistry.deactivate(owner)
        assertTrue(
            ActiveConversationRegistry.shouldIncrementUnread(
                incomingMessage(chatId = "group", chatType = 2, senderId = "member")
            )
        )
    }

    @Test
    fun `direct message matches the visible peer by sender id`() {
        ActiveConversationRegistry.activate(Any(), ConversationKey("friend", 1))

        assertFalse(
            ActiveConversationRegistry.shouldIncrementUnread(
                incomingMessage(chatId = "current-user", chatType = 1, senderId = "friend")
            )
        )
    }

    @Test
    fun `outgoing messages never increment unread`() {
        val outgoing = incomingMessage(
            chatId = "group",
            chatType = 2,
            senderId = "current-user"
        ).copy(direction = "right")

        assertFalse(ActiveConversationRegistry.shouldIncrementUnread(outgoing))
    }

    private fun incomingMessage(chatId: String, chatType: Int, senderId: String) = MessageItem(
        msgId = "message-id",
        senderId = senderId,
        senderName = "Sender",
        senderAvatar = "",
        chatId = chatId,
        chatType = chatType,
        content = "message",
        contentType = MessageItem.CONTENT_TYPE_TEXT,
        timestamp = 1L,
        direction = "left"
    )
}
