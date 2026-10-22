package com.juhao.murexide.repository

import com.juhao.murexide.data.BotItem
import com.juhao.murexide.data.InstructionItem
import com.juhao.murexide.data.InstructionWebListResponse
import com.juhao.murexide.data.toInstructionItem
import com.juhao.murexide.network.NetworkClient
import com.juhao.murexide.proto.group.bot_list
import com.juhao.murexide.proto.group.bot_list_send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class InstructionRepository {
    private val client = NetworkClient.okHttpClient
    private val baseUrl = NetworkClient.BASE_URL
    private val json = Json { ignoreUnknownKeys = true }

    /** 群聊：一次拿到群内机器人及其指令 (protobuf) */
    suspend fun getGroupBots(
        token: String,
        groupId: String
    ): Result<Pair<List<BotItem>, List<InstructionItem>>> {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = bot_list_send(group_id = groupId)
                    .encode()
                    .toRequestBody("application/octet-stream".toMediaType())

                val httpRequest = Request.Builder()
                    .url("$baseUrl/v1/group/bot-list")
                    .post(requestBody)
                    .header("token", token)
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP error: ${response.code}"))
                    }
                    val result = bot_list.ADAPTER.decode(response.body.bytes())
                    if (result.status?.code != 1) {
                        return@withContext Result.failure(
                            Exception(result.status?.msg?.ifEmpty { "获取群机器人失败" } ?: "获取群机器人失败")
                        )
                    }

                    val bots = result.bot.map {
                        BotItem(
                            id = it.bot_id,
                            name = it.name,
                            avatarUrl = it.avatar_url,
                            introduction = it.introduction
                        )
                    }
                    val instructions = result.instruction.map {
                        InstructionItem(
                            id = it.id,
                            botId = it.bot_id,
                            name = it.name,
                            desc = it.desc,
                            type = it.type,
                            hintText = it.hint_text,
                            defaultText = it.default_text,
                            form = it.form,
                            botName = it.bot_name
                        )
                    }
                    Result.success(bots to instructions)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** 机器人私聊：拿单个机器人的指令 (JSON web-list) */
    suspend fun getBotInstructions(
        token: String,
        botId: String,
        botName: String = ""
    ): Result<List<InstructionItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val bodyJson = """{"botId":"$botId"}"""
                val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

                val httpRequest = Request.Builder()
                    .url("$baseUrl/v1/instruction/web-list")
                    .post(requestBody)
                    .header("token", token)
                    .build()

                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP error: ${response.code}"))
                    }
                    val result = json.decodeFromString(
                        InstructionWebListResponse.serializer(),
                        response.body.string()
                    )
                    if (result.code != 1) {
                        return@withContext Result.failure(
                            Exception(result.msg.ifEmpty { "获取机器人指令失败" })
                        )
                    }
                    val instructions = (result.data?.list ?: emptyList())
                        .map { it.toInstructionItem(botName = botName) }
                    Result.success(instructions)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
