package com.juhao.murexide.repository

import com.juhao.murexide.data.BoardItem
import com.juhao.murexide.network.NetworkClient
import com.juhao.murexide.proto.bot.board
import com.juhao.murexide.proto.bot.board_send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 群看板加载：POST /v1/bot/board
 * 一个群可能有多个机器人看板，返回列表。
 */
class BoardRepository {
    private val client = NetworkClient.okHttpClient
    private val baseUrl = NetworkClient.BASE_URL

    suspend fun getBoard(
        token: String,
        chatId: String,
        chatType: Int
    ): Result<List<BoardItem>> = withContext(Dispatchers.IO) {
        try {
            val body = board_send(
                chat_id = chatId,
                chat_type = chatType.toLong()
            ).encode()

            val request = Request.Builder()
                .url("$baseUrl/v1/bot/board")
                .post(body.toRequestBody("application/octet-stream".toMediaType()))
                .header("token", token)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure(Exception("HTTP error: ${response.code}"))
                }
                val result = board.ADAPTER.decode(response.body.bytes())
                if (result.status?.code != 1) {
                    return@use Result.failure(Exception(result.status?.msg ?: "加载失败"))
                }
                val boards = result.yh_bot_board
                    .filter { it.content.isNotBlank() }
                    .sortedByDescending { it.last_update_time }
                    .map {
                        BoardItem(
                            botId = it.bot_id,
                            botName = it.bot_name,
                            content = it.content,
                            contentType = it.content_type,
                            lastUpdateTime = it.last_update_time
                        )
                    }
                Result.success(boards)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
