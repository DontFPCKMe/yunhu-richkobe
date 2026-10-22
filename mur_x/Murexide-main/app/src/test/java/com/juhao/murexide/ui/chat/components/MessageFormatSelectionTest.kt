package com.juhao.murexide.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFormatSelectionTest {
    @Test
    fun noHorizontalMovement_keepsInitialMarkdownOption() {
        assertEquals(
            2,
            sendFormatOptionIndex(
                horizontalDrag = 0f,
                initialIndex = 2,
                optionWidth = 96f,
                optionCount = 3
            )
        )
    }

    @Test
    fun slidingLeft_selectsHtmlThenCancel() {
        assertEquals(1, sendFormatOptionIndex(-60f, 2, 96f, 3))
        assertEquals(0, sendFormatOptionIndex(-150f, 2, 96f, 3))
    }

    @Test
    fun largeHorizontalMovement_clampsToEdgeOptions() {
        assertEquals(0, sendFormatOptionIndex(-500f, 2, 96f, 3))
        assertEquals(2, sendFormatOptionIndex(500f, 0, 96f, 3))
    }
}
