package com.juhao.murexide.repository

import com.juhao.murexide.data.CreatedChat
import com.juhao.murexide.network.NetworkClient
import com.juhao.murexide.proto.bot.create_bot
import com.juhao.murexide.proto.bot.create_bot_send
import com.juhao.murexide.proto.group.create_group
import com.juhao.murexide.proto.group.create_group_send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val protobufMediaType = "application/octet-stream".toMediaType()

internal fun createGroupBody(name: String, introduction: String, avatarUrl: String) =
    create_group_send(name = name, introduction = introduction, avatar_url = avatarUrl)
        .encode().toRequestBody(protobufMediaType)

internal fun createBotBody(name: String, introduction: String, avatarUrl: String, isPrivate: Boolean) =
    create_bot_send(name = name, introduction = introduction, avatar_url = avatarUrl, private_ = if (isPrivate) 1 else 0)
        .encode().toRequestBody(protobufMediaType)

class CreationRepository {
    private val client = NetworkClient.okHttpClient

    suspend fun createGroup(token: String, name: String, introduction: String, avatarUrl: String): Result<CreatedChat> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${NetworkClient.BASE_URL}/v1/group/create-group")
                    .post(createGroupBody(name, introduction, avatarUrl))
                    .header("token", token)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use Result.failure(Exception("HTTP error: ${response.code}"))
                    val result = create_group.ADAPTER.decode(response.body.bytes())
                    if (result.status?.code != 1) return@use Result.failure(Exception(result.status?.msg ?: "创建群聊失败"))
                    Result.success(CreatedChat(result.group_id, 2, name, avatarUrl))
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    suspend fun createBot(token: String, name: String, introduction: String, avatarUrl: String, isPrivate: Boolean): Result<CreatedChat> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${NetworkClient.BASE_URL}/v1/bot/create-bot")
                    .post(createBotBody(name, introduction, avatarUrl, isPrivate))
                    .header("token", token)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use Result.failure(Exception("HTTP error: ${response.code}"))
                    val result = create_bot.ADAPTER.decode(response.body.bytes())
                    if (result.status?.code != 1) return@use Result.failure(Exception(result.status?.msg ?: "创建机器人失败"))
                    val id = result.data_?.bot_id.orEmpty()
                    if (id.isBlank()) return@use Result.failure(Exception("创建机器人返回了空 ID"))
                    Result.success(CreatedChat(id, 3, name, avatarUrl))
                }
            } catch (e: Exception) { Result.failure(e) }
        }
}
