package com.juhao.murexide.ui.theme

import androidx.compose.runtime.mutableStateOf
import com.juhao.murexide.data.ConversationItem
import com.juhao.murexide.data.StickyItem

object UiState {
    var themeMode = mutableStateOf("system")
    var themeColor = mutableStateOf("DYNAMIC")
    var squareAvatar = mutableStateOf(false)
}

object UiCache {
    var conversation = mutableStateOf<List<ConversationItem>>(emptyList())
    var stickyConversations = mutableStateOf<List<StickyItem>>(emptyList())

    fun clearAccountData() {
        conversation.value = emptyList()
        stickyConversations.value = emptyList()
    }
}
