package com.juhao.murexide.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSearchRepositoryTest {
    private val repository = HomeSearchRepository()
    private val json = Json

    @Test
    fun `normalizes documented categorized search response`() {
        val root = json.parseToJsonElement(
            """{"code":1,"data":{"list":[
                {"title":"用户","list":[{"friendId":"u1","friendType":1,"nickname":"用户","avatarUrl":"u"}]},
                {"title":"群组","list":[{"friendId":"g1","friendType":2,"nickname":"群聊","avatarUrl":"g"}]},
                {"title":"机器人","list":[{"friendId":"b1","friendType":3,"nickname":"机器人","avatarUrl":"b"}]}
            ]}}"""
        ).jsonObject

        val results = repository.parseHomeSearchResults(root)!!

        assertEquals(listOf(1, 2, 3), results.map { it.chatType })
        assertEquals(listOf("u1", "g1", "b1"), results.map { it.chatId })
    }

    @Test
    fun `returns null for an unknown response shape`() {
        val root = json.parseToJsonElement("""{"code":1,"data":{"unexpected":true}}""").jsonObject

        assertNull(repository.parseHomeSearchResults(root))
    }
}
