package com.juhao.murexide.ui.chat.components

import android.text.style.ReplacementSpan
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.juhao.murexide.data.DefaultEmoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultEmojiTextFieldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun platformMagnifierIsSuppressedWithoutVisibleRotation() {
        instrumentation.runOnMainSync {
            val editor = DefaultEmojiEditText(instrumentation.targetContext)
            val value = TextFieldValue("")
            editor.bind(
                value = value,
                mentions = emptyList(),
                emojis = emptyList(),
                enabled = true,
                textColor = Color.Black,
                hintColor = Color.Gray,
                textSizeSp = 16f,
                onValueChanged = { _, _, _, _ -> },
                onFocused = {},
                suppressPlatformMagnifier = true
            )

            assertEquals(Float.MIN_VALUE, editor.rotation)
            assertTrue(editor.rotation != 0f)

            editor.bind(
                value = value,
                mentions = emptyList(),
                emojis = emptyList(),
                enabled = true,
                textColor = Color.Black,
                hintColor = Color.Gray,
                textSizeSp = 16f,
                onValueChanged = { _, _, _, _ -> },
                onFocused = {},
                suppressPlatformMagnifier = false
            )
            assertEquals(0f, editor.rotation)
        }
    }

    @Test
    fun adjacentEmojiRemainRenderedAfterProgrammaticInsertions() {
        instrumentation.runOnMainSync {
            val editor = DefaultEmojiEditText(instrumentation.targetContext)
            val emojis = listOf(
                DefaultEmoji("猪头"),
                DefaultEmoji("OK"),
                DefaultEmoji("笑哭")
            )
            val markers = emojis.map(DefaultEmoji::marker)

            markers.indices.forEach { lastMarkerIndex ->
                val text = markers.take(lastMarkerIndex + 1).joinToString("")
                editor.bind(
                    value = TextFieldValue(text, TextRange(text.length)),
                    mentions = emptyList(),
                    emojis = emojis,
                    enabled = true,
                    textColor = Color.Black,
                    hintColor = Color.Gray,
                    textSizeSp = 16f,
                    onValueChanged = { _, _, _, _ -> },
                    onFocused = {}
                )

                val spans = editor.editableText
                    .getSpans(0, text.length, ReplacementSpan::class.java)
                val actualRanges = spans
                    .map { editor.editableText.getSpanStart(it) to editor.editableText.getSpanEnd(it) }
                    .sortedBy(Pair<Int, Int>::first)
                var start = 0
                val expectedRanges = markers.take(lastMarkerIndex + 1).map { marker ->
                    (start to start + marker.length).also { start += marker.length }
                }

                assertEquals(expectedRanges, actualRanges)
            }
        }
    }

    @Test
    fun deletingMiddleRepeatedEmojiKeepsRemainingEmojiRenderedAfterStateRebind() {
        instrumentation.runOnMainSync {
            val editor = DefaultEmojiEditText(instrumentation.targetContext)
            val emoji = DefaultEmoji("猪头")
            val emojis = listOf(emoji)
            val markers = List(3) { emoji.marker }
            val originalText = markers.joinToString("")
            var reboundValue = TextFieldValue(originalText, TextRange(originalText.length))
            editor.bind(
                value = reboundValue,
                mentions = emptyList(),
                emojis = emojis,
                enabled = true,
                textColor = Color.Black,
                hintColor = Color.Gray,
                textSizeSp = 16f,
                onValueChanged = { value, _, _, _ -> reboundValue = value },
                onFocused = {}
            )

            val middleEnd = markers.first().length + markers[1].length
            editor.setSelection(middleEnd)
            val inputConnection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(inputConnection.deleteSurroundingText(1, 0))
            editor.bind(
                value = reboundValue,
                mentions = emptyList(),
                emojis = emojis,
                enabled = true,
                textColor = Color.Black,
                hintColor = Color.Gray,
                textSizeSp = 16f,
                onValueChanged = { value, _, _, _ -> reboundValue = value },
                onFocused = {}
            )

            val expectedMarkers = listOf(markers.first(), markers.last())
            val expectedText = expectedMarkers.joinToString("")
            assertEquals(expectedText, editor.text.toString())
            val spans = editor.editableText
                .getSpans(0, expectedText.length, ReplacementSpan::class.java)
            val actualRanges = spans
                .map { editor.editableText.getSpanStart(it) to editor.editableText.getSpanEnd(it) }
                .sortedBy(Pair<Int, Int>::first)
            var start = 0
            val expectedRanges = expectedMarkers.map { marker ->
                (start to start + marker.length).also { start += marker.length }
            }
            assertEquals(expectedRanges, actualRanges)
        }
    }

    @Test
    fun surroundingDeletionAtEmojiBoundaryDeletesOnlyPrecedingEmoji() {
        instrumentation.runOnMainSync {
            val editor = DefaultEmojiEditText(instrumentation.targetContext)
            val emojis = listOf(
                DefaultEmoji("猪头"),
                DefaultEmoji("OK"),
                DefaultEmoji("笑哭")
            )
            val markers = emojis.map(DefaultEmoji::marker)
            val originalText = markers.joinToString("")
            var reboundValue = TextFieldValue(originalText, TextRange(originalText.length))
            editor.bind(
                value = reboundValue,
                mentions = emptyList(),
                emojis = emojis,
                enabled = true,
                textColor = Color.Black,
                hintColor = Color.Gray,
                textSizeSp = 16f,
                onValueChanged = { value, _, _, _ -> reboundValue = value },
                onFocused = {}
            )

            val middleEnd = markers[0].length + markers[1].length
            editor.setSelection(middleEnd)
            val inputConnection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(inputConnection.deleteSurroundingText(1, 1))
            editor.bind(
                value = reboundValue,
                mentions = emptyList(),
                emojis = emojis,
                enabled = true,
                textColor = Color.Black,
                hintColor = Color.Gray,
                textSizeSp = 16f,
                onValueChanged = { value, _, _, _ -> reboundValue = value },
                onFocused = {}
            )

            val expectedMarkers = listOf(markers.first(), markers.last())
            val expectedText = expectedMarkers.joinToString("")
            assertEquals(expectedText, editor.text.toString())
            val spans = editor.editableText
                .getSpans(0, expectedText.length, ReplacementSpan::class.java)
            val actualRanges = spans
                .map { editor.editableText.getSpanStart(it) to editor.editableText.getSpanEnd(it) }
                .sortedBy(Pair<Int, Int>::first)
            var start = 0
            val expectedRanges = expectedMarkers.map { marker ->
                (start to start + marker.length).also { start += marker.length }
            }
            assertEquals(expectedRanges, actualRanges)
        }
    }
}
