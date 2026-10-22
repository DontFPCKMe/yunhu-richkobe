package com.juhao.murexide.utils

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

object DeviceIdProvider {
    private const val PREFERENCES_NAME = "installation_identity"
    private const val DEVICE_ID_KEY = "device_id"

    @Synchronized
    fun get(context: Context): String {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        return preferences.getString(DEVICE_ID_KEY, null)
            ?: "android_${UUID.randomUUID()}".also { deviceId ->
                preferences.edit { putString(DEVICE_ID_KEY, deviceId) }
            }
    }
}
