package com.juhao.murexide.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScrollTest {
    @Test
    fun centeredItemNeedsNoAdditionalScroll() {
        val distance = calculateItemCenterScrollDistance(
            itemOffset = 400,
            itemSize = 200,
            viewportStartOffset = 0,
            viewportEndOffset = 1_000
        )

        assertEquals(0f, distance)
    }

    @Test
    fun itemBelowViewportCenterScrollsForwardByTheCenterDifference() {
        val distance = calculateItemCenterScrollDistance(
            itemOffset = 700,
            itemSize = 100,
            viewportStartOffset = -40,
            viewportEndOffset = 960
        )

        assertEquals(290f, distance)
    }

    @Test
    fun itemAboveViewportCenterScrollsBackwardByTheCenterDifference() {
        val distance = calculateItemCenterScrollDistance(
            itemOffset = 100,
            itemSize = 120,
            viewportStartOffset = 20,
            viewportEndOffset = 820
        )

        assertEquals(-260f, distance)
    }
}
