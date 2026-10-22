package com.juhao.murexide.repository

import com.juhao.murexide.data.ContactRequestItem
import com.juhao.murexide.proto.friend.request_list
import com.juhao.murexide.proto.friend.request_list_send
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendRepositoryRequestTest {

    @Test
    fun requestListBodyUsesEmptyProtobufPayload() {
        val body = createFriendRequestListBody()
        val buffer = Buffer()

        body.writeTo(buffer)
        val payload = buffer.readByteArray()

        assertEquals("application/octet-stream", body.contentType().toString())
        assertEquals(0L, body.contentLength())
        assertArrayEquals(request_list_send().encode(), payload)
        assertEquals(request_list_send(), request_list_send.ADAPTER.decode(payload))
    }

    @Test
    fun alternateInviterIdFromFieldEightIsDecoded() {
        val payload = request_list.Request(alternateInviterId = "8418077").encode()
        val decoded = request_list.Request.ADAPTER.decode(payload)

        assertEquals("", decoded.inviterId)
        assertEquals("8418077", decoded.alternateInviterId)
        assertEquals(
            "8418077",
            resolveInviterId(decoded.inviterId, decoded.alternateInviterId)
        )
    }

    @Test
    fun primaryInviterIdTakesPrecedenceOverAlternateField() {
        assertEquals("primary", resolveInviterId("primary", "alternate"))
    }

    @Test
    fun rejectingGroupInvitationUsesFriendEndpoint() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "{\"code\":1,\"msg\":\"success\"}"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val repository = FriendRepository(client = client, baseUrl = "https://example.test")
        val groupInvitation = contactRequest(
            inviterId = "8418077",
            sourceType = 2,
            targetType = 1,
            groupName = "测试群聊"
        )

        val result = repository.respondToRequest(
            token = "token-value",
            requestId = groupInvitation.requestId,
            agree = 2,
            usesGroupAgreeInvite = groupInvitation.usesGroupAgreeInvite
        )

        assertTrue(result.isSuccess)
        assertFalse(groupInvitation.usesGroupAgreeInvite)
        assertEquals(
            "https://example.test/v1/friend/agree-apply",
            captured?.url?.toString()
        )
        assertEquals("token-value", captured?.header("token"))
        val requestJson = Buffer().also { captured?.body?.writeTo(it) }.readUtf8()
        val requestObject = Json.parseToJsonElement(requestJson).jsonObject
        assertEquals("7", requestObject.getValue("id").jsonPrimitive.content)
        assertEquals("2", requestObject.getValue("agree").jsonPrimitive.content)
    }

    @Test
    fun acceptingGroupInvitationUsesFriendEndpoint() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "{\"code\":1,\"msg\":\"success\"}"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val repository = FriendRepository(client = client, baseUrl = "https://example.test")
        val groupInvitation = contactRequest(
            inviterId = "8418077",
            sourceType = 2,
            targetType = 1,
            groupName = "测试群聊"
        )

        val result = repository.respondToRequest(
            token = "token-value",
            requestId = groupInvitation.requestId,
            agree = 1,
            usesGroupAgreeInvite = groupInvitation.usesGroupAgreeInvite
        )

        assertTrue(result.isSuccess)
        assertFalse(groupInvitation.usesGroupAgreeInvite)
        assertEquals(
            "https://example.test/v1/friend/agree-apply",
            captured?.url?.toString()
        )
    }

    @Test
    fun acceptingGroupApplicationUsesGroupEndpoint() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "{\"code\":1,\"msg\":\"success\"}"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val repository = FriendRepository(client = client, baseUrl = "https://example.test")
        val groupApplication = contactRequest(
            inviterId = "",
            sourceType = 1,
            targetType = 2,
            groupName = "测试群聊"
        )

        val result = repository.respondToRequest(
            token = "token-value",
            requestId = groupApplication.requestId,
            agree = 1,
            usesGroupAgreeInvite = groupApplication.usesGroupAgreeInvite
        )

        assertTrue(result.isSuccess)
        assertTrue(groupApplication.usesGroupAgreeInvite)
        assertEquals(
            "https://example.test/v1/group/agree-invite",
            captured?.url?.toString()
        )
    }

    @Test
    fun groupApplicationAndInvitationHaveDistinctLabels() {
        val groupInvitation = contactRequest(
            inviterId = "8418077",
            sourceType = 2,
            targetType = 1,
            groupName = "测试群聊"
        )
        val groupApplication = contactRequest(
            inviterId = "",
            sourceType = 1,
            targetType = 2,
            groupName = "测试群聊"
        )

        assertEquals("群聊邀请", groupInvitation.typeLabel)
        assertEquals("群聊申请", groupApplication.typeLabel)
    }

    @Test
    fun friendApplicationKeepsUsingFriendEndpoint() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "{\"code\":1,\"msg\":\"success\"}"
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()
        val repository = FriendRepository(client = client, baseUrl = "https://example.test")
        val friendApplication = contactRequest(
            inviterId = "",
            sourceType = 1,
            targetType = 1
        )

        val result = repository.respondToRequest(
            token = "token-value",
            requestId = friendApplication.requestId,
            agree = 2,
            usesGroupAgreeInvite = friendApplication.usesGroupAgreeInvite
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "https://example.test/v1/friend/agree-apply",
            captured?.url?.toString()
        )
    }

    private fun contactRequest(
        inviterId: String,
        sourceType: Int,
        targetType: Int,
        groupName: String = ""
    ) = ContactRequestItem(
        requestId = 7,
        requesterName = "申请人",
        requesterAvatarUrl = "",
        receiverName = "接收人",
        receiverAvatarUrl = "",
        groupName = groupName,
        groupAvatarUrl = "",
        botName = "",
        botAvatarUrl = "",
        inviterId = inviterId,
        sourceType = sourceType,
        targetType = targetType,
        targetId = "target-id",
        receiverId = "receiver-id",
        result = 0,
        processedAt = 0,
        invitedAt = 0,
        invitedAtText = "",
        processorName = "",
        note = ""
    )
}
