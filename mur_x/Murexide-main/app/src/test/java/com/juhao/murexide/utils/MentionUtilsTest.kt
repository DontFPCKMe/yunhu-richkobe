package com.juhao.murexide.utils

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.juhao.murexide.data.MentionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionUtilsTest {
    @Test
    fun `same display names retain both selected user ids`() {
        val first = MentionUtils.insertMention(
            text = "",
            mentions = emptyList(),
            userId = "user-1",
            displayName = "Ann"
        )
        val second = MentionUtils.insertMention(
            text = first.text,
            mentions = first.mentions,
            userId = "user-2",
            displayName = "Ann"
        )

        assertEquals("@Ann @Ann ", second.text)
        assertEquals(
            listOf("user-1", "user-2"),
            MentionUtils.mentionedUserIds(second.text, second.mentions)
        )
    }

    @Test
    fun `plain prefix match does not create a mention`() {
        assertTrue(MentionUtils.mentionedUserIds("@Anna", emptyList()).isEmpty())

        val anna = MentionToken(
            userId = "anna-id",
            displayName = "Anna",
            start = 0,
            endExclusive = 5
        )
        assertEquals(
            listOf("anna-id"),
            MentionUtils.mentionedUserIds("@Anna", listOf(anna))
        )
    }

    @Test
    fun `text inserted before mentions shifts their ranges`() {
        val original = MentionUtils.insertMention(
            text = "",
            mentions = emptyList(),
            userId = "user-1",
            displayName = "Ann"
        )
        val edited = MentionUtils.processEdit(
            old = TextFieldValue(original.text, TextRange(0)),
            new = TextFieldValue("Hi ${original.text}", TextRange(3)),
            mentions = original.mentions
        )

        assertEquals(3, edited.mentions.single().start)
        assertEquals(
            listOf("user-1"),
            MentionUtils.mentionedUserIds(edited.value.text, edited.mentions)
        )
    }

    @Test
    fun `partial deletion removes the entire mention token`() {
        val mention = MentionToken(
            userId = "user-1",
            displayName = "Ann",
            start = 0,
            endExclusive = 4
        )
        val edited = MentionUtils.processEdit(
            old = TextFieldValue("@Ann hello", TextRange(4)),
            new = TextFieldValue("@An hello", TextRange(3)),
            mentions = listOf(mention)
        )

        assertEquals(" hello", edited.value.text)
        assertTrue(edited.mentions.isEmpty())
    }

    @Test
    fun `partial deletion removes an entire protected emoji marker`() {
        val text = "你好[.猪头]世界"
        val markerStart = 2
        val markerEnd = markerStart + "[.猪头]".length
        val edited = MentionUtils.processEdit(
            old = TextFieldValue(text, TextRange(markerEnd)),
            new = TextFieldValue("你好[.猪]世界", TextRange(markerEnd - 1)),
            mentions = emptyList(),
            protectedRanges = listOf(TextRange(markerStart, markerEnd))
        )

        assertEquals("你好世界", edited.value.text)
        assertEquals(TextRange(markerStart), edited.value.selection)
    }

    @Test
    fun `selection cannot remain inside a protected emoji marker`() {
        val text = "A[.猪头]B"
        val markerStart = 1
        val markerEnd = markerStart + "[.猪头]".length
        val edited = MentionUtils.processEdit(
            old = TextFieldValue(text, TextRange(markerEnd)),
            new = TextFieldValue(text, TextRange(markerStart + 2)),
            mentions = emptyList(),
            protectedRanges = listOf(TextRange(markerStart, markerEnd))
        )

        assertEquals(TextRange(markerStart), edited.value.selection)
    }

    @Test
    fun `replacing selection with emoji marker shifts following mention`() {
        val mention = MentionToken(
            userId = "user-1",
            displayName = "Ann",
            start = 6,
            endExclusive = 10
        )
        val result = MentionUtils.replaceRange(
            text = "hello @Ann ",
            mentions = listOf(mention),
            selection = TextRange(0, 5),
            replacement = "[.猪头]"
        )

        assertEquals("[.猪头] @Ann ", result.text)
        assertEquals("[.猪头] ".length, result.mentions.single().start)
        assertEquals(TextRange("[.猪头]".length), result.selection)
    }

    @Test
    fun `actual edit range deletes only the targeted adjacent emoji`() {
        val markers = listOf("[.猪头]", "[.OK]", "[.笑哭]")
        val oldText = markers.joinToString("")
        val middleStart = markers.first().length
        val newText = markers.first() + markers.last()
        val protectedRanges = buildList {
            var start = 0
            markers.forEach { marker ->
                add(TextRange(start, start + marker.length))
                start += marker.length
            }
        }

        val edited = MentionUtils.processEdit(
            old = TextFieldValue(oldText, TextRange(middleStart + markers[1].length)),
            new = TextFieldValue(newText, TextRange(middleStart)),
            mentions = emptyList(),
            protectedRanges = protectedRanges,
            textEdit = MentionUtils.TextEdit(
                start = middleStart,
                beforeCount = markers[1].length,
                afterCount = 0
            )
        )

        assertEquals(newText, edited.value.text)
        assertEquals(TextRange(middleStart), edited.value.selection)
    }
}
