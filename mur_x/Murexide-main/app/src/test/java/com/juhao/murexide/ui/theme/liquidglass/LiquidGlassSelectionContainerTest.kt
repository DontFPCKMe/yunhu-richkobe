package com.juhao.murexide.ui.theme.liquidglass

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassSelectionContainerTest {
    @Test
    fun removesPlatformMagnifierBeforeComposeMaterializesIt() {
        val marker = object : Modifier.Element {}
        val modifier = marker.then(Modifier.composed { Modifier })

        val filtered = modifier.withoutPlatformMagnifier()

        assertFalse(filtered.any { it.javaClass.name == "androidx.compose.ui.ComposedModifier" })
        assertTrue(filtered.any { it === marker })
        assertSame(marker, filtered)
    }
}
