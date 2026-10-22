package com.juhao.murexide.data

import kotlinx.serialization.Serializable

@Serializable
data class StickyItem(
    val id: Long,
    val chatType: Int,
    val chatId: String,
    val chatName: String,
    val avatarUrl: String,
    val certificationLevel: Int
)
