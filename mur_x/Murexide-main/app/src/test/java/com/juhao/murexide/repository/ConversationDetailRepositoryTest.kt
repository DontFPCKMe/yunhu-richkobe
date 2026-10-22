package com.juhao.murexide.repository

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDetailRepositoryTest {
    @Test
    fun `http 200 with business error does not report nickname edit success`() = runBlocking {
        val repository = repositoryReturning("""{"code": 0, "msg": "没有修改权限"}""")

        val result = repository.editMyGroupNickname(
            token = "token",
            groupId = "group-1",
            nickname = "new nickname"
        )

        assertTrue(result.isFailure)
        assertEquals("没有修改权限", result.exceptionOrNull()?.message)
    }

    @Test
    fun `business code one reports nickname edit success`() = runBlocking {
        val repository = repositoryReturning("""{"code": 1, "msg": "success"}""")

        val result = repository.editMyGroupNickname(
            token = "token",
            groupId = "group-1",
            nickname = "new nickname"
        )

        assertTrue(result.isSuccess)
    }

    private fun repositoryReturning(responseJson: String): ConversationDetailRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return ConversationDetailRepository(
            client = client,
            baseUrl = "https://example.test"
        )
    }
}
