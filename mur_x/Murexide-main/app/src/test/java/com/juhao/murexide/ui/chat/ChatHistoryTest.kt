package com.juhao.murexide.ui.chat

import com.juhao.murexide.data.MessageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {
    @Test
    fun gappedCacheCannotMoveTheServerHistoryCursor() {
        val cached = listOf(
            message("latest-1"),
            message("latest-2"),
            message("stale-old-1"),
            message("stale-old-2")
        )
        val latestServerPage = listOf(
            message("latest-1"),
            message("latest-2"),
            message("missing-middle-1")
        )

        val snapshot = resolveServerHistorySnapshot(
            existingMessages = cached,
            serverMessages = latestServerPage
        )

        assertEquals(
            listOf("latest-1", "latest-2", "missing-middle-1"),
            snapshot.messages.map { it.msgId }
        )
        assertEquals("missing-middle-1", snapshot.nextAnchorMessageId)
        assertTrue(snapshot.hasMore)
    }

    @Test
    fun emptyServerSnapshotClearsCachedHistoryCursor() {
        val snapshot = resolveServerHistorySnapshot(
            existingMessages = listOf(message("cached")),
            serverMessages = emptyList()
        )

        assertTrue(snapshot.messages.isEmpty())
        assertEquals(null, snapshot.nextAnchorMessageId)
        assertFalse(snapshot.hasMore)
    }

    @Test
    fun olderPage_keepsServerOrderAndSkipsKnownOrDuplicateMessages() {
        val page = resolveOlderMessagePage(
            knownMessageIds = setOf("current", "known"),
            currentAnchorMessageId = "current",
            messages = listOf(
                message("known"),
                message("older-1"),
                message("older-1"),
                message("older-2")
            )
        )

        assertEquals(listOf("older-1", "older-2"), page.newMessages.map { it.msgId })
        assertEquals("older-2", page.nextAnchorMessageId)
        assertTrue(page.madeCursorProgress)
    }

    @Test
    fun repeatedAnchor_stopsPaginationEvenWhenTheServerReturnsMessages() {
        val page = resolveOlderMessagePage(
            knownMessageIds = setOf("current"),
            currentAnchorMessageId = "current",
            messages = listOf(message("older"), message("current"))
        )

        assertEquals(listOf("older"), page.newMessages.map { it.msgId })
        assertEquals("current", page.nextAnchorMessageId)
        assertFalse(page.madeCursorProgress)
    }

    @Test
    fun blankPageHasNoAnchorProgress() {
        val page = resolveOlderMessagePage(
            knownMessageIds = emptySet(),
            currentAnchorMessageId = "current",
            messages = emptyList()
        )

        assertTrue(page.newMessages.isEmpty())
        assertEquals(null, page.nextAnchorMessageId)
        assertFalse(page.madeCursorProgress)
    }

    @Test
    fun cachedPageCanDisplayDisconnectedLocalHistoryWithoutChangingServerSemantics() {
        val page = resolveCachedHistoryPage(
            knownMessageIds = setOf("latest", "cached-known"),
            currentAnchorMessageId = "latest",
            messages = listOf(
                message("cached-known"),
                message("cached-old-1"),
                message("cached-old-2")
            ),
            pageSize = 3
        )

        assertEquals(listOf("cached-old-1", "cached-old-2"), page.newMessages.map { it.msgId })
        assertEquals("cached-old-2", page.nextAnchorMessage?.msgId)
        assertTrue(page.madeCursorProgress)
        assertTrue(page.hasMore)
    }

    @Test
    fun shortCachedPageMarksOfflineHistoryComplete() {
        val page = resolveCachedHistoryPage(
            knownMessageIds = setOf("latest"),
            currentAnchorMessageId = "latest",
            messages = listOf(message("cached-last")),
            pageSize = 20
        )

        assertEquals(listOf("cached-last"), page.newMessages.map { it.msgId })
        assertFalse(page.hasMore)
    }

    private fun message(id: String) = MessageItem(
        msgId = id,
        senderId = "sender",
        senderName = "Sender",
        senderAvatar = "",
        contentType = MessageItem.CONTENT_TYPE_TEXT,
        timestamp = 0,
        direction = "left"
    )
}
