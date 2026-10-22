package com.juhao.murexide.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCacheDatabaseTest {
    private lateinit var database: LocalCacheDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LocalCacheDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun conversationsAreIsolatedByAccountAndReplacementIsAtomic() = runBlocking {
        val dao = database.conversations()
        dao.replaceConversations("account-a", listOf(conversation("account-a", "one")))
        dao.replaceConversations("account-b", listOf(conversation("account-b", "two")))
        dao.replaceConversations("account-a", listOf(conversation("account-a", "three")))

        assertEquals(listOf("three"), dao.observeConversations("account-a").first().map { it.chatId })
        assertEquals(listOf("two"), dao.observeConversations("account-b").first().map { it.chatId })
    }

    @Test
    fun messageQueryStaysWithinConversationAndReturnsNewestFirst() = runBlocking {
        val dao = database.messages()
        dao.upsertMessages(
            listOf(
                message("account", "one", "chat-a", 100L),
                message("account", "two", "chat-a", 200L),
                message("account", "three", "chat-b", 300L)
            )
        )

        val ids = dao.observeMessages("account", "chat-a", 1, 20).first().map { it.msgId }
        assertEquals(listOf("two", "one"), ids)
    }

    @Test
    fun messageQueryPagesBeforeStableCursorWithoutOffset() = runBlocking {
        val dao = database.messages()
        dao.upsertMessages(
            listOf(
                message("account", "a", "chat", 100L),
                message("account", "b", "chat", 100L),
                message("account", "c", "chat", 100L),
                message("account", "newer", "chat", 200L)
            )
        )

        val ids = dao.getMessagesBefore(
            accountId = "account",
            chatId = "chat",
            chatType = 1,
            beforeTimestamp = 100L,
            beforeMsgSeq = 100L,
            beforeMsgId = "c",
            limit = 20
        ).map { it.msgId }

        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun conversationQueryPreservesServerPositionInsteadOfResortingByTime() = runBlocking {
        val dao = database.conversations()
        dao.replaceConversations(
            "account",
            listOf(
                conversation("account", "server-first", listPosition = 0, timestamp = 1L),
                conversation("account", "server-second", listPosition = 1, timestamp = 9_999L)
            )
        )

        assertEquals(
            listOf("server-first", "server-second"),
            dao.observeConversations("account").first().map { it.chatId }
        )
    }

    private fun conversation(
        accountId: String,
        chatId: String,
        listPosition: Int = 0,
        timestamp: Long = 1L
    ) = CachedConversationEntity(
        accountId = accountId,
        chatType = 1,
        chatId = chatId,
        name = chatId,
        remark = null,
        chatContent = "message",
        timestampMs = timestamp,
        unreadMessage = 0,
        at = 0,
        avatarUrl = "",
        doNotDisturb = 0,
        certificationLevel = 0,
        sendTimestamp = timestamp,
        latestMessageId = null,
        latestMessageSeq = 0L,
        latestContentType = 1,
        listPosition = listPosition
    )

    private fun message(accountId: String, msgId: String, chatId: String, timestamp: Long) =
        CachedMessageEntity(
            accountId = accountId,
            msgId = msgId,
            chatId = chatId,
            chatType = 1,
            senderId = "sender",
            senderType = 1,
            timestamp = timestamp,
            msgSeq = timestamp,
            updateTimestamp = timestamp,
            payload = "{}"
        )
}
