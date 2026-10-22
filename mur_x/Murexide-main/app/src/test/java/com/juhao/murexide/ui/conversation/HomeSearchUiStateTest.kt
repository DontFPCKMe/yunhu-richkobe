package com.juhao.murexide.ui.conversation

import com.juhao.murexide.data.HomeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSearchUiStateTest {
    @Test
    fun `limits each search result category to its first five entries`() {
        val results = buildList {
            repeat(6) { index -> add(searchResult("user$index", 1)) }
            repeat(6) { index -> add(searchResult("group$index", 2)) }
            repeat(6) { index -> add(searchResult("bot$index", 3)) }
        }
        val state = HomeSearchUiState(results = results)

        assertEquals((0..4).map { "user$it" }, state.resultsFor(1).map { it.chatId })
        assertEquals((0..4).map { "group$it" }, state.resultsFor(2).map { it.chatId })
        assertEquals((0..4).map { "bot$it" }, state.resultsFor(3).map { it.chatId })
    }

    @Test
    fun `keeps categories with five or fewer results unchanged`() {
        val results = listOf(
            searchResult("user", 1),
            searchResult("group", 2),
            searchResult("bot", 3)
        )
        val state = HomeSearchUiState(results = results)

        assertEquals(results[0], state.resultsFor(1).single())
        assertEquals(results[1], state.resultsFor(2).single())
        assertEquals(results[2], state.resultsFor(3).single())
    }

    private fun searchResult(id: String, type: Int) = HomeSearchResult(
        chatId = id,
        chatType = type,
        name = id,
        avatarUrl = "",
        introduction = ""
    )
}
