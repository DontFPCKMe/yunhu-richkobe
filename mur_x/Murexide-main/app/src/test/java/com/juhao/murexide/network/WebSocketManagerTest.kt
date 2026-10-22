package com.juhao.murexide.network

import com.juhao.murexide.proto.chat_ws_go.INFO
import com.juhao.murexide.proto.chat_ws_go.WsMsg
import com.juhao.murexide.proto.chat_ws_go.edit_message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketManagerTest {
    @Test
    fun editMessagePayload_decodesAsEditedMessage() {
        val event = decodeEditMessageEvent(editPayload(deleteTime = 0))

        assertTrue(event is WebSocketManager.WsEvent.EditMessage)
        val message = (event as WebSocketManager.WsEvent.EditMessage).message
        assertEquals("message-id", message.msgId)
        assertEquals("chat-id", message.chatId)
        assertEquals("updated", message.content)
        assertFalse(message.isRecalled)
        assertTrue(message.isEdited)
    }

    @Test
    fun recalledEditMessagePayload_decodesAsDeletedEvent() {
        val event = decodeEditMessageEvent(editPayload(deleteTime = 1_234))

        assertTrue(event is WebSocketManager.WsEvent.MessageDeleted)
        val deletedEvent = event as WebSocketManager.WsEvent.MessageDeleted
        val message = deletedEvent.message
        assertEquals("message-id", message.msgId)
        assertEquals("chat-id", message.chatId)
        assertEquals(2, message.chatType)
        assertEquals(1_000L, message.timestamp)
        assertEquals(42L, message.msgSeq)
        assertTrue(message.isRecalled)
        assertFalse(message.hasReliableSender)
        assertEquals("owner-id", message.recalledById)
        assertEquals("Group owner", message.recalledByName)

        val actor = deletedEvent.actor
        assertNotNull(actor)
        assertEquals("owner-id", actor?.id)
        assertEquals("Group owner", actor?.name)
    }

    @Test
    fun botRecallPush_decodesAsDeletedEventWithUnreliableSender() {
        val event = WsMsg(
            msg_id = "message-id",
            sender = WsMsg.WsSender(
                chat_id = "bot-id",
                chat_type = 3,
                name = "Moderator bot"
            ),
            chat_id = "chat-id",
            chat_type = 2,
            content_type = 1,
            timestamp = 1_000,
            delete_time = 1_234,
            msg_seq = 42
        ).toPushEvent(currentUserId = "my-user-id")

        assertTrue(event is WebSocketManager.WsEvent.MessageDeleted)
        val deletedEvent = event as WebSocketManager.WsEvent.MessageDeleted
        assertTrue(deletedEvent.message.isRecalled)
        assertFalse(deletedEvent.message.hasReliableSender)
        assertEquals("left", deletedEvent.message.direction)
        assertEquals("bot-id", deletedEvent.actor?.id)
        assertEquals("Moderator bot", deletedEvent.actor?.name)
    }

    private fun editPayload(deleteTime: Long): ByteArray {
        val payload = edit_message(
            info = INFO(cmd = "edit_message"),
            data_ = edit_message.EditData(
                msg = WsMsg(
                    msg_id = "message-id",
                    sender = WsMsg.WsSender(
                        chat_id = "owner-id",
                        name = "Group owner"
                    ),
                    chat_id = "chat-id",
                    chat_type = 2,
                    content = WsMsg.WsContent(text = "updated"),
                    content_type = 1,
                    timestamp = 1_000,
                    delete_time = deleteTime,
                    msg_seq = 42,
                    edit_time = 2_000
                )
            )
        )
        return edit_message.ADAPTER.encode(payload)
    }
}
