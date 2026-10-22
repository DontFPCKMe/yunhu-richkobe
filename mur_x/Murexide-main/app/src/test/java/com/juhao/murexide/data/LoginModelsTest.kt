package com.juhao.murexide.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginModelsTest {
    @Test
    fun `logout request uses documented device id field`() {
        val body = Json.encodeToString(
            LogoutRequest.serializer(),
            LogoutRequest(deviceId = "android-installation-id")
        )

        assertEquals("{\"device-id\":\"android-installation-id\"}", body)
    }
}
