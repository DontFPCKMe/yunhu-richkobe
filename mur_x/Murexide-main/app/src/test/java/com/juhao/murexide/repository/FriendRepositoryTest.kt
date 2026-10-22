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

class FriendRepositoryTest {
    @Test
    fun `mute request uses documented no-notify endpoint and value one`() = runBlocking {
        var path = ""
        var token = ""
        var body = ""
        val repository = FriendRepository(
            client = OkHttpClient.Builder().addInterceptor { chain ->
                path = chain.request().url.encodedPath
                token = chain.request().header("token").orEmpty()
                body = chain.request().body!!.let { requestBody ->
                    val buffer = okio.Buffer()
                    requestBody.writeTo(buffer)
                    buffer.readUtf8()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"code\":1,\"msg\":\"success\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
            baseUrl = "https://example.test"
        )

        val result = repository.setNoNotify("token-value", "group-1", muted = true)

        assertTrue(result.isSuccess)
        assertEquals("/v1/friend/no-notify", path)
        assertEquals("token-value", token)
        assertEquals("{\"chatId\":\"group-1\",\"noNotify\":1}", body)
    }

    @Test
    fun `inviting a bot uses group invite contract`() = runBlocking {
        var path = ""
        var payload = ""
        val repository = FriendRepository(
            client = OkHttpClient.Builder().addInterceptor { chain ->
                path = chain.request().url.encodedPath
                payload = chain.request().body!!.let { requestBody ->
                    val buffer = okio.Buffer()
                    requestBody.writeTo(buffer)
                    buffer.readUtf8()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"code\":1,\"msg\":\"success\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
            baseUrl = "https://example.test"
        )

        val result = repository.inviteBotToGroup("token", "bot-1", "group-1")

        assertTrue(result.isSuccess)
        assertEquals("/v1/group/invite", path)
        assertTrue(payload.contains("\"chatId\":\"bot-1\""))
        assertTrue(payload.contains("\"chatType\":3"))
        assertTrue(payload.contains("\"groupId\":\"group-1\""))
    }
}
