package com.juhao.murexide.data

import androidx.compose.runtime.Immutable

@Immutable
data class ForwardTarget(
    val chatId: String,
    val chatType: Int,
    val displayName: String,
    val avatarUrl: String,
    val searchText: String = displayName,
    val isPinned: Boolean = false
) {
    val key: Pair<Int, String>
        get() = chatType to chatId

    fun toConversationItem(): ConversationItem = ConversationItem(
        chatId = chatId,
        chatType = chatType,
        name = displayName,
        chatContent = "",
        timestampMs = 0L,
        avatarUrl = avatarUrl
    )
}

internal fun mergeForwardTargets(
    conversations: List<ConversationItem>,
    stickyItems: List<StickyItem>
): List<ForwardTarget> {
    val conversationsByKey = conversations.associateBy { it.chatType to it.chatId }
    val pinnedKeys = mutableSetOf<Pair<Int, String>>()

    val pinned = stickyItems.mapNotNull { sticky ->
        val key = sticky.chatType to sticky.chatId
        if (!pinnedKeys.add(key)) return@mapNotNull null
        val conversation = conversationsByKey[key]
        ForwardTarget(
            chatId = sticky.chatId,
            chatType = sticky.chatType,
            displayName = conversation?.displayName
                ?: sticky.chatName.ifBlank { sticky.chatId },
            avatarUrl = conversation?.avatarUrl?.ifBlank { sticky.avatarUrl }
                ?: sticky.avatarUrl,
            searchText = listOfNotNull(
                conversation?.displayName,
                conversation?.name,
                conversation?.remark,
                sticky.chatName
            ).distinct().joinToString(" "),
            isPinned = true
        )
    }

    val normal = conversations.mapNotNull { conversation ->
        val key = conversation.chatType to conversation.chatId
        if (key in pinnedKeys) return@mapNotNull null
        ForwardTarget(
            chatId = conversation.chatId,
            chatType = conversation.chatType,
            displayName = conversation.displayName.ifBlank { conversation.chatId },
            avatarUrl = conversation.avatarUrl,
            searchText = listOfNotNull(
                conversation.displayName,
                conversation.name,
                conversation.remark
            ).distinct().joinToString(" ")
        )
    }
    return (pinned + normal).distinctBy { it.key }
}

internal fun List<ForwardTarget>.filterForwardTargets(query: String): List<ForwardTarget> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return this
    return filter { it.searchText.contains(normalized, ignoreCase = true) }
}
