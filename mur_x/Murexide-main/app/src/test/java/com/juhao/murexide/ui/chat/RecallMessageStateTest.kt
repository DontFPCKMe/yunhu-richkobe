package com.juhao.murexide.ui.chat

import com.juhao.murexide.data.MessageItem
import com.juhao.murexide.network.RecallActor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallMessageStateTest {
    @Test
    fun `applying recall changes status without replacing original author`() {
        val original = message(
            id = "target",
            senderId = "member-id",
            senderName = "Member",
            direction = "left"
        )
        val eventMessage = message(
            id = "target",
            senderId = "",
            senderName = "",
            direction = "left"
        ).copy(isRecalled = true, deleteTime = 1234, hasReliableSender = false)

        val result = listOf(original).withRecalledMessage(
            recalledMessage = eventMessage,
            actor = RecallActor(id = "owner-id", name = "Group owner")
        ).single()

        assertTrue(result.isRecalled)
        assertEquals(1234L, result.deleteTime)
        assertEquals("member-id", result.senderId)
        assertEquals("Member", result.senderName)
        assertEquals("left", result.direction)
        assertEquals("owner-id", result.recalledById)
        assertEquals("Group owner", result.recalledByName)
        assertTrue(result.hasReliableSender)
    }

    @Test
    fun `recall for another id leaves messages unchanged`() {
        val original = message("target", "member-id", "Member", "left")
        val recalled = message("other", "", "", "left").copy(isRecalled = true)

        val result = listOf(original).withRecalledMessage(recalled)

        assertEquals(listOf(original), result)
        assertFalse(result.single().isRecalled)
    }

    @Test
    fun `recalled bubble is separated from adjacent messages by the same author`() {
        val messages = listOf(
            message("newer", "member-id", "Member", "left"),
            message("recalled", "member-id", "Member", "left").copy(isRecalled = true),
            message("older", "member-id", "Member", "left")
        )

        val items = computeDisplayItems(
            messages = messages,
            chatType = 2,
            ownerId = null,
            adminIds = emptySet()
        )
        val recalled = items.single { it.message.msgId == "recalled" }

        assertTrue(recalled.isFirstFromSender)
        assertTrue(recalled.isLastFromSender)
        assertEquals("member-id", recalled.message.senderId)
        assertEquals("Member", recalled.message.senderName)
    }

    private fun message(
        id: String,
        senderId: String,
        senderName: String,
        direction: String
    ) = MessageItem(
        msgId = id,
        senderId = senderId,
        senderName = senderName,
        senderAvatar = "avatar",
        chatId = "group-id",
        chatType = 2,
        content = "content",
        contentType = MessageItem.CONTENT_TYPE_TEXT,
        timestamp = 1,
        direction = direction
    )
}
