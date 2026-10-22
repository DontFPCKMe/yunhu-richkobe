package com.juhao.murexide.ui.chat

import com.juhao.murexide.data.MessageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageGalleryTest {
    @Test
    fun gallery_keepsImagesAndVideosInOrderButSkipsStickersAndInvalidMedia() {
        val messages = listOf(
            message("new-image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "new.jpg", msgSeq = 30),
            message("video", MessageItem.CONTENT_TYPE_VIDEO, videoUrl = "clip.mp4", msgSeq = 20),
            message(
                "sticker",
                MessageItem.CONTENT_TYPE_STICKER,
                imageUrl = "sticker-in-image-field.webp",
                stickerUrl = "sticker.webp"
            ),
            message("blank-image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "  "),
            message("recalled-image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "recalled.jpg", isRecalled = true),
            message("blank-video", MessageItem.CONTENT_TYPE_VIDEO, videoUrl = " "),
            message("old-image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "old.jpg", msgSeq = 5)
        )

        val gallery = buildChatMediaGallery(messages, selectedMessageId = "new-image")!!

        assertEquals(listOf("old-image", "video", "new-image"), gallery.entries.map { it.messageId })
        assertEquals(listOf("old.jpg", "clip.mp4", "new.jpg"), gallery.entries.map { it.url })
        assertEquals(listOf(5L, 20L, 30L), gallery.entries.map { it.sequence })
        assertEquals(
            listOf(ChatMediaKind.IMAGE, ChatMediaKind.VIDEO, ChatMediaKind.IMAGE),
            gallery.entries.map { it.kind }
        )
        assertEquals(2, gallery.initialIndex)
        assertEquals("video", gallery.entries[gallery.initialIndex - 1].messageId)
    }

    @Test
    fun videoSelection_opensTheSameMixedMediaGallery() {
        val messages = listOf(
            message("new-image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "new.jpg"),
            message("video", MessageItem.CONTENT_TYPE_VIDEO, videoUrl = "clip.mp4"),
            message("old-image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "old.jpg")
        )

        val gallery = buildChatMediaGallery(messages, selectedMessageId = "video")!!

        assertEquals(listOf("old-image", "video", "new-image"), gallery.entries.map { it.messageId })
        assertEquals(1, gallery.initialIndex)
    }

    @Test
    fun gallery_usesMessageIdWhenDifferentMessagesShareAUrl() {
        val messages = listOf(
            message("new-copy", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "same.jpg"),
            message("old-copy", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "same.jpg")
        )

        val gallery = buildChatMediaGallery(messages, selectedMessageId = "new-copy")!!

        assertEquals(1, gallery.initialIndex)
    }

    @Test
    fun stickerSelection_doesNotCreateAPhotoGallery() {
        val messages = listOf(
            message("image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "photo.jpg"),
            message("sticker", MessageItem.CONTENT_TYPE_STICKER, stickerUrl = "sticker.webp")
        )

        assertNull(buildChatMediaGallery(messages, selectedMessageId = "sticker"))
    }

    @Test
    fun fullMessagePageWithoutMedia_continuesLoadingFromNextAnchor() {
        val messages = List(20) { index ->
            message("text-$index", MessageItem.CONTENT_TYPE_TEXT)
        }

        val page = buildEarlierChatMediaPage(
            messages = messages,
            knownMessageIds = emptySet(),
            currentAnchorMessageId = "current",
            pageSize = 20
        )

        assertEquals(emptyList<ChatMediaGalleryEntry>(), page.entries)
        assertEquals("text-19", page.nextAnchorMessageId)
        assertTrue(page.hasMoreMessages)
        assertTrue(page.shouldContinueLoading)
    }

    @Test
    fun messagePageWithMedia_stopsEmptyPageScanAndKeepsMixedMedia() {
        val messages = List(18) { index ->
            message("text-$index", MessageItem.CONTENT_TYPE_TEXT)
        } + listOf(
            message("video", MessageItem.CONTENT_TYPE_VIDEO, videoUrl = "clip.mp4"),
            message("image", MessageItem.CONTENT_TYPE_IMAGE, imageUrl = "photo.jpg")
        )

        val page = buildEarlierChatMediaPage(
            messages = messages,
            knownMessageIds = emptySet(),
            currentAnchorMessageId = "current",
            pageSize = 20
        )

        assertEquals(listOf("image", "video"), page.entries.map { it.messageId })
        assertTrue(page.hasMoreMessages)
        assertFalse(page.shouldContinueLoading)
    }

    private fun message(
        id: String,
        contentType: Int,
        imageUrl: String? = null,
        videoUrl: String? = null,
        stickerUrl: String? = null,
        isRecalled: Boolean = false,
        msgSeq: Long = 0
    ) = MessageItem(
        msgId = id,
        senderId = "sender",
        senderName = "Sender",
        senderAvatar = "",
        contentType = contentType,
        timestamp = 0,
        msgSeq = msgSeq,
        direction = "left",
        isRecalled = isRecalled,
        imageUrl = imageUrl,
        videoUrl = videoUrl,
        stickerUrl = stickerUrl
    )
}
