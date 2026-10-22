package com.juhao.murexide.ui.chat

import com.juhao.murexide.data.ChatUiState
import com.juhao.murexide.data.MentionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatComposerStateTest {
    @Test
    fun `typing changes composer projection without changing screen projection`() {
        val before = ChatUiState(isSending = false)
        val after = before.copy(
            inputText = "hello",
            inputSelectionStart = 5,
            inputSelectionEnd = 5,
            mentions = listOf(MentionToken("1", "Alice", 0, 5))
        )

        assertEquals(before.withoutComposerFields(), after.withoutComposerFields())
        assertNotEquals(before.toComposerState(), after.toComposerState())
        assertEquals("hello", after.toComposerState().text)
    }

    @Test
    fun `non composer changes still invalidate screen projection`() {
        val before = ChatUiState()
        val after = before.copy(isSending = true)

        assertNotEquals(before.withoutComposerFields(), after.withoutComposerFields())
        assertTrue(after.withoutComposerFields().isSending)
    }
}
