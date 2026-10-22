package com.juhao.murexide.data

/** A user, group, or bot returned by the home search endpoint. */
data class HomeSearchResult(
    val chatId: String,
    val chatType: Int,
    val name: String,
    val avatarUrl: String = "",
    val introduction: String = ""
)

data class CreatedChat(
    val chatId: String,
    val chatType: Int,
    val name: String,
    val avatarUrl: String
)

data class ConversationKey(val chatId: String, val chatType: Int)

fun List<ConversationItem>.unreadTotal(excluding: ConversationKey? = null): Int {
    val total = asSequence()
        .filterNot { it.doNotDisturb == 1 || (excluding != null && it.chatId == excluding.chatId && it.chatType == excluding.chatType) }
        .sumOf { it.unreadMessage.coerceAtLeast(0).toLong() }
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
