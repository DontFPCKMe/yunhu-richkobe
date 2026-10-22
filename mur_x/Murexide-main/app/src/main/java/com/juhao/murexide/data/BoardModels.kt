package com.juhao.murexide.data

/**
 * 群看板条目。一个群可有多个机器人各自的看板。
 * contentType：1-文本 2-markdown 3-html
 */
data class BoardItem(
    val botId: String,
    val botName: String,
    val content: String,
    val contentType: Int,
    val lastUpdateTime: Long
) {
    companion object {
        const val CONTENT_TYPE_TEXT = 1
        const val CONTENT_TYPE_MARKDOWN = 2
        const val CONTENT_TYPE_HTML = 3
    }
}

/** 聊天顶栏看板面板状态 */
data class BoardPanelState(
    val boards: List<BoardItem> = emptyList(),
    val isExpanded: Boolean = false,
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false
)
