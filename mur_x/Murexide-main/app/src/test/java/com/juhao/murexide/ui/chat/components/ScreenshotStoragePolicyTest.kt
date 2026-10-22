package com.juhao.murexide.ui.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotStoragePolicyTest {
    @Test
    fun android9_requiresWritePermissionWhenItIsMissing() {
        assertTrue(requiresLegacyWritePermission(sdkInt = 28, permissionGranted = false))
    }

    @Test
    fun android9_doesNotRequestAnAlreadyGrantedPermission() {
        assertFalse(requiresLegacyWritePermission(sdkInt = 28, permissionGranted = true))
    }

    @Test
    fun android10AndLater_doNotRequireLegacyWritePermission() {
        assertFalse(requiresLegacyWritePermission(sdkInt = 29, permissionGranted = false))
    }
}
