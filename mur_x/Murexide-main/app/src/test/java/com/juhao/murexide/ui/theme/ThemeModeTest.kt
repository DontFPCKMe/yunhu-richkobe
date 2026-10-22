package com.juhao.murexide.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.Color
import org.junit.Test

class ThemeModeTest {

    @Test
    fun whiteThemeFollowsExplicitAndSystemDarkModes() {
        assertFalse(usesDarkTheme("light", systemInDarkTheme = true))
        assertTrue(usesDarkTheme("dark", systemInDarkTheme = false))
        assertFalse(usesDarkTheme("system", systemInDarkTheme = false))
        assertTrue(usesDarkTheme("system", systemInDarkTheme = true))
    }

    @Test
    fun whiteDarkPaletteUsesDarkSurfaces() {
        assertNotEquals(WhiteLightColorScheme.surface, WhiteDarkColorScheme.surface)
        assertNotEquals(WhiteLightColorScheme.onSurface, WhiteDarkColorScheme.onSurface)
        assertTrue(WhiteDarkColorScheme.surfaceContainerHighest == Color(0xFF191919))
        assertTrue(WhiteDarkColorScheme.surfaceContainer == Color(0xFF111111))
        assertTrue(WhiteDarkColorScheme.surfaceContainerHigh == Color(0xFF2C2C2C))
    }

    @Test
    fun liquidGlassContentColorKeepsReadablePreferredColor() {
        val preferredColor = Color(0xFF202020)

        assertEquals(
            preferredColor,
            liquidGlassContentColor(
                preferredColor = preferredColor,
                glassColor = Color.White.copy(alpha = 0.5f),
                backgroundColor = Color.White,
            )
        )
    }

    @Test
    fun liquidGlassContentColorFlipsUnreadableNeutrals() {
        assertEquals(
            Color.Black,
            liquidGlassContentColor(
                preferredColor = Color.White,
                glassColor = Color.White.copy(alpha = 0.5f),
                backgroundColor = Color.White,
            )
        )
        assertEquals(
            Color.White,
            liquidGlassContentColor(
                preferredColor = Color.Black,
                glassColor = Color.Black.copy(alpha = 0.8f),
                backgroundColor = Color.White,
            )
        )
    }

    @Test
    fun liquidGlassContentColorAccountsForForegroundAlpha() {
        val translucentBlack = Color.Black.copy(alpha = 0.5f)

        assertEquals(
            Color.Black,
            liquidGlassContentColor(
                preferredColor = translucentBlack,
                glassColor = Color.White,
                backgroundColor = Color.White,
            )
        )
        assertEquals(
            translucentBlack,
            liquidGlassContentColor(
                preferredColor = translucentBlack,
                glassColor = Color.White,
                backgroundColor = Color.White,
                minimumContrast = 3f,
            )
        )
    }
}
