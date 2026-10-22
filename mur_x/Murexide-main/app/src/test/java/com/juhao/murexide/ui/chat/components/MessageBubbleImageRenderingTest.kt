package com.juhao.murexide.ui.chat.components

import com.juhao.murexide.data.MessageItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleImageRenderingTest {
    @Test
    fun `chat images use software bitmaps`() {
        assertFalse(
            chatMediaAllowsHardwareBitmaps(MessageItem.CONTENT_TYPE_IMAGE)
        )
    }

    @Test
    fun `other chat media keep hardware bitmaps enabled`() {
        assertTrue(
            chatMediaAllowsHardwareBitmaps(MessageItem.CONTENT_TYPE_STICKER)
        )
        assertTrue(
            chatMediaAllowsHardwareBitmaps(MessageItem.CONTENT_TYPE_VIDEO)
        )
    }
}
