package com.juhao.murexide.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRepositoryTest {
    @Test
    fun `explicit refresh bypasses the cached conversation md5`() {
        assertEquals(
            "",
            conversationListRequestMd5(cachedMd5 = "cached-md5", forceRefresh = true)
        )
    }

    @Test
    fun `background sync retains the cached conversation md5`() {
        assertEquals(
            "cached-md5",
            conversationListRequestMd5(cachedMd5 = "cached-md5", forceRefresh = false)
        )
    }
}
