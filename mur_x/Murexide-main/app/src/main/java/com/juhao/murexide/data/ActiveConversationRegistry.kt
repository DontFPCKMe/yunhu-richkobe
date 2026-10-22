package com.juhao.murexide.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentHashMap

/** Tracks chat screens that are resumed and therefore consuming incoming messages as read. */
internal object ActiveConversationRegistry {
    private val conversationsByOwner = ConcurrentHashMap<Any, ConversationKey>()
    private val readRequestChannel = Channel<ConversationKey>(Channel.UNLIMITED)

    val readRequests: Flow<ConversationKey> = readRequestChannel.receiveAsFlow()

    fun activate(owner: Any, conversation: ConversationKey) {
        conversationsByOwner[owner] = conversation
        requestRead(conversation)
    }

    fun deactivate(owner: Any) {
        conversationsByOwner.remove(owner)
    }

    fun activeKeyFor(message: MessageItem): ConversationKey? =
        conversationsByOwner.values.firstOrNull { conversation ->
            conversation.chatType == message.chatType &&
                (conversation.chatId == message.chatId ||
                    (message.chatType == 1 &&
                        message.senderId.isNotBlank() &&
                        conversation.chatId == message.senderId))
        }

    fun shouldIncrementUnread(message: MessageItem): Boolean =
        !message.isMine && activeKeyFor(message) == null

    fun requestRead(conversation: ConversationKey) {
        readRequestChannel.trySend(conversation)
    }

    internal fun resetForTests() {
        conversationsByOwner.clear()
        while (readRequestChannel.tryReceive().isSuccess) {
            // Drain requests left by the previous test.
        }
    }
}
