package com.juhao.murexide.ui.theme.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiquidGlassControlsTest {
    @Test
    fun continuousSliderClampsToRange() {
        assertEquals(0.2f, snapSliderValue(0f, 0.2f..1f, steps = 0))
        assertEquals(0.65f, snapSliderValue(0.65f, 0.2f..1f, steps = 0))
        assertEquals(1f, snapSliderValue(2f, 0.2f..1f, steps = 0))
    }

    @Test
    fun steppedSliderSnapsToIncludingEndpoints() {
        val range = 0f..4f
        assertEquals(0f, snapSliderValue(0.2f, range, steps = 3))
        assertEquals(2f, snapSliderValue(1.6f, range, steps = 3))
        assertEquals(4f, snapSliderValue(3.8f, range, steps = 3))
    }

    @Test
    fun navigationIndexRoundsAndClamps() {
        assertEquals(0, snapNavigationIndex(-2f, tabsCount = 5))
        assertEquals(2, snapNavigationIndex(1.6f, tabsCount = 5))
        assertEquals(4, snapNavigationIndex(8f, tabsCount = 5))
    }

    @Test
    fun navigationRejectsEmptyTabs() {
        assertThrows(IllegalArgumentException::class.java) {
            snapNavigationIndex(0f, tabsCount = 0)
        }
    }
}
