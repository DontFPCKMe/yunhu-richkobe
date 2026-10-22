package com.juhao.murexide.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardModelsTest {
    @Test
    fun `sticky conversations stay first and duplicate keys are removed`() {
        val normal = listOf(
            ConversationItem(
                chatId = "normal",
                chatType = 1,
                name = "普通会话",
                remark = null,
                chatContent = "",
                timestampMs = 2,
                avatarUrl = "normal-avatar"
            ),
            ConversationItem(
                chatId = "pinned",
                chatType = 2,
                name = "群原名",
                remark = "群备注",
                chatContent = "",
                timestampMs = 1,
                avatarUrl = "group-avatar"
            )
        )
        val sticky = listOf(
            StickyItem(1, 2, "pinned", "群名称", "sticky-avatar", 0),
            StickyItem(2, 1, "sticky-only", "置顶好友", "sticky-only-avatar", 0)
        )

        val result = mergeForwardTargets(normal, sticky)

        assertEquals(
            listOf("pinned", "sticky-only", "normal"),
            result.map { it.chatId }
        )
        assertEquals("群备注", result.first().displayName)
        assertEquals("group-avatar", result.first().avatarUrl)
        assertTrue(result.first().isPinned)
    }

    @Test
    fun `search matches display name and original name without changing order`() {
        val targets = listOf(
            ForwardTarget("1", 1, "备注甲", "", searchText = "备注甲 原名称"),
            ForwardTarget("2", 1, "乙", "", searchText = "乙")
        )

        assertEquals(listOf("1"), targets.filterForwardTargets("原名称").map { it.chatId })
        assertEquals(targets, targets.filterForwardTargets(" "))
    }
}
