package com.juhao.murexide.repository

import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryForwardTest {
    @Test
    fun `forward request preserves source type and recipient order`() {
        val body = createForwardMessageJson(
            msgId = "source-message",
            chatType = 2,
            recipients = listOf(
                ForwardReceiveRequest(chatId = "first", chatType = 1),
                ForwardReceiveRequest(chatId = "second", chatType = 2)
            )
        )

        val request = Json.decodeFromString<ForwardMessageRequest>(body)
        assertEquals("source-message", request.msgId)
        assertEquals(2, request.chatType)
        assertEquals(listOf("first", "second"), request.receive.map { it.chatId })
        assertEquals(listOf(1, 2), request.receive.map { it.chatType })
    }

    @Test
    fun `forward posts json to documented endpoint with token`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"code\":1,\"msg\":\"success\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val repository = MessageRepository(client = client, baseUrl = "https://example.test")

        val result = repository.forwardMessage(
            token = "token-value",
            msgId = "source-message",
            sourceChatType = 2,
            recipients = listOf(ForwardReceiveRequest("target", 1))
        )

        assertTrue(result.isSuccess)
        assertEquals("https://example.test/v1/msg/msg-forward", captured?.url?.toString())
        assertEquals("token-value", captured?.header("token"))
        assertEquals(
            "application/json",
            captured?.body?.contentType()?.toString()?.substringBefore(';')
        )
    }
}
