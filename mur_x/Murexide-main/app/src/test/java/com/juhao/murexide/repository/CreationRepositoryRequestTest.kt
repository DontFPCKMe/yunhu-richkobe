package com.juhao.murexide.repository

import com.juhao.murexide.proto.bot.create_bot_send
import com.juhao.murexide.proto.group.create_group_send
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class CreationRepositoryRequestTest {
    @Test
    fun `group creation uses documented protobuf fields`() {
        val body = createGroupBody("群聊", "简介", "https://avatar")
        val buffer = Buffer()
        body.writeTo(buffer)

        assertEquals(
            create_group_send(name = "群聊", introduction = "简介", avatar_url = "https://avatar"),
            create_group_send.ADAPTER.decode(buffer.readByteArray())
        )
    }

    @Test
    fun `bot privacy is encoded as zero or one`() {
        val privateBuffer = Buffer().also { createBotBody("机器人", "", "", true).writeTo(it) }
        val publicBuffer = Buffer().also { createBotBody("机器人", "", "", false).writeTo(it) }

        assertEquals(1, create_bot_send.ADAPTER.decode(privateBuffer.readByteArray()).private_)
        assertEquals(0, create_bot_send.ADAPTER.decode(publicBuffer.readByteArray()).private_)
    }
}
