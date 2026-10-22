package com.juhao.murexide.repository

import com.juhao.murexide.data.MessageContent
import com.juhao.murexide.data.MessageItem
import com.juhao.murexide.data.MessageMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageRepositoryMediaTest {
    @Test
    fun `send request preserves default emoji markers as ordinary text`() {
        val request = createSendMessageRequest(
            msgId = "message-id",
            chatId = "chat-id",
            chatType = 2,
            content = MessageContent(text = "这是一个猪头表情:[.猪头]"),
            contentType = MessageItem.CONTENT_TYPE_TEXT,
            quoteMsgId = null,
            commandId = null
        )

        assertEquals("这是一个猪头表情:[.猪头]", requireNotNull(request.content).text)
        assertEquals(MessageItem.CONTENT_TYPE_TEXT.toLong(), request.content_type)
        assertEquals("", requireNotNull(request.content).image)
        assertEquals(0L, requireNotNull(request.content).sticker_item_id)
        assertEquals(0L, requireNotNull(request.content).sticker_pack_id)
    }

    @Test
    fun `send request includes uploaded media dimensions and identity`() {
        val request = createSendMessageRequest(
            msgId = "message-id",
            chatId = "chat-id",
            chatType = 2,
            content = MessageContent(
                image = "photo.webp",
                media = MessageMedia(
                    fileKey = "photo.webp",
                    fileHash = "hash",
                    fileType = "image/webp",
                    width = 1200,
                    height = 1600,
                    fileSize = 42,
                    fileSuffix = "webp"
                )
            ),
            contentType = MessageItem.CONTENT_TYPE_IMAGE,
            quoteMsgId = null,
            commandId = null
        )

        assertNotNull(request.media)
        val media = requireNotNull(request.media)
        assertEquals("photo.webp", media.file_key)
        assertEquals("photo.webp", media.file_key2)
        assertEquals("hash", media.file_hash)
        assertEquals("image/webp", media.file_type)
        assertEquals(1200L, media.image_width)
        assertEquals(1600L, media.image_height)
        assertEquals(42L, media.file_size)
        assertEquals("webp", media.file_suffix)
    }
}
