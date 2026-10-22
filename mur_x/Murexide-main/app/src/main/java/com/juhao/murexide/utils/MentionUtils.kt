package com.juhao.murexide.utils

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.juhao.murexide.data.MentionToken

object MentionUtils {
    data class TextEdit(
        val start: Int,
        val beforeCount: Int,
        val afterCount: Int
    )

    data class EditResult(
        val value: TextFieldValue,
        val mentions: List<MentionToken>,
        val insertedText: String,
        val insertPos: Int
    )

    data class InsertResult(
        val text: String,
        val mentions: List<MentionToken>,
        val selection: TextRange
    )

    data class ReplaceResult(
        val text: String,
        val mentions: List<MentionToken>,
        val selection: TextRange
    )

    fun validMentions(text: String, mentions: List<MentionToken>): List<MentionToken> {
        val result = mutableListOf<MentionToken>()
        var previousEnd = -1
        mentions.sortedBy { it.start }.forEach { mention ->
            val isValidRange = mention.start >= 0 &&
                mention.endExclusive == mention.start + mention.displayText.length &&
                mention.endExclusive <= text.length
            if (
                isValidRange &&
                mention.start >= previousEnd &&
                text.regionMatches(
                    thisOffset = mention.start,
                    other = mention.displayText,
                    otherOffset = 0,
                    length = mention.displayText.length
                )
            ) {
                result += mention
                previousEnd = mention.endExclusive
            }
        }
        return result
    }

    fun mentionedUserIds(text: String, mentions: List<MentionToken>): List<String> {
        return validMentions(text, mentions).map { it.userId }.distinct()
    }

    fun insertMention(
        text: String,
        mentions: List<MentionToken>,
        userId: String,
        displayName: String,
        triggerPos: Int = -1
    ): InsertResult {
        require(displayName.isNotEmpty())
        val currentMentions = validMentions(text, mentions)
        val replacesTrigger = triggerPos in text.indices && text[triggerPos] == '@'
        val insertAt = if (replacesTrigger) triggerPos else text.length
        val replaceEnd = if (replacesTrigger) triggerPos + 1 else insertAt
        val displayText = "@$displayName"
        val insertedText = "$displayText "
        val newText = text.substring(0, insertAt) + insertedText + text.substring(replaceEnd)
        val shiftedMentions = transformMentions(
            mentions = currentMentions,
            editStart = insertAt,
            editEnd = replaceEnd,
            insertedLength = insertedText.length
        )
        val newMention = MentionToken(
            userId = userId,
            displayName = displayName,
            start = insertAt,
            endExclusive = insertAt + displayText.length
        )
        return InsertResult(
            text = newText,
            mentions = (shiftedMentions + newMention).sortedBy { it.start },
            selection = TextRange(insertAt + insertedText.length)
        )
    }

    fun processEdit(
        old: TextFieldValue,
        new: TextFieldValue,
        mentions: List<MentionToken>,
        protectedRanges: List<TextRange> = emptyList(),
        textEdit: TextEdit? = null
    ): EditResult {
        val currentMentions = validMentions(old.text, mentions)
        val currentProtectedRanges = validProtectedRanges(old.text, protectedRanges)
        val atomicRanges = (
            currentMentions.map { TextRange(it.start, it.endExclusive) } +
                currentProtectedRanges
            ).sortedBy { it.start }
        if (old.text == new.text) {
            return EditResult(
                new.copy(
                    selection = clampSelection(
                        text = new.text,
                        selection = new.selection,
                        mentions = currentMentions,
                        protectedRanges = currentProtectedRanges
                    )
                ),
                currentMentions,
                "", -1
            )
        }

        val oldText = old.text
        val newText = new.text

        val validatedTextEdit = textEdit?.takeIf { edit ->
            isValidTextEdit(oldText, newText, edit)
        }
        val delStart: Int
        val delEnd: Int
        val inserted: String
        if (validatedTextEdit != null) {
            delStart = validatedTextEdit.start
            delEnd = delStart + validatedTextEdit.beforeCount
            inserted = newText.substring(
                delStart,
                delStart + validatedTextEdit.afterCount
            )
        } else {
            var prefix = 0
            val minLen = minOf(oldText.length, newText.length)
            while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
            var suffix = 0
            while (suffix < minLen - prefix &&
                oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
            ) suffix++
            delStart = prefix
            delEnd = oldText.length - suffix
            inserted = newText.substring(prefix, newText.length - suffix)
        }

        if (delStart == delEnd) {
            for (range in atomicRanges) {
                if (delStart > range.start && delStart < range.end) {
                    val insertAt = range.end
                    val result = oldText.substring(0, insertAt) + inserted + oldText.substring(insertAt)
                    val updatedMentions = transformMentions(
                        currentMentions,
                        insertAt,
                        insertAt,
                        inserted.length
                    )
                    val updatedProtectedRanges = transformRanges(
                        currentProtectedRanges,
                        insertAt,
                        insertAt,
                        inserted.length
                    )
                    return EditResult(
                        TextFieldValue(
                            result,
                            clampSelection(
                                text = result,
                                selection = TextRange(insertAt + inserted.length),
                                mentions = updatedMentions,
                                protectedRanges = updatedProtectedRanges
                            )
                        ),
                        updatedMentions,
                        inserted,
                        insertAt
                    )
                }
            }
        }

        var editStart = delStart
        var editEnd = delEnd
        if (delStart < delEnd) {
            var expanded: Boolean
            do {
                expanded = false
                atomicRanges.forEach { range ->
                    val intersects = editStart < range.end && editEnd > range.start
                    val fullyCovered = editStart <= range.start && editEnd >= range.end
                    if (intersects && !fullyCovered) {
                        val expandedStart = minOf(editStart, range.start)
                        val expandedEnd = maxOf(editEnd, range.end)
                        if (expandedStart != editStart || expandedEnd != editEnd) {
                            editStart = expandedStart
                            editEnd = expandedEnd
                            expanded = true
                        }
                    }
                }
            } while (expanded)
        }

        val resultText = oldText.substring(0, editStart) + inserted + oldText.substring(editEnd)
        val updatedMentions = transformMentions(
            currentMentions,
            editStart,
            editEnd,
            inserted.length
        )
        val updatedProtectedRanges = transformRanges(
            currentProtectedRanges,
            editStart,
            editEnd,
            inserted.length
        )
        val selection = if (editStart == delStart && editEnd == delEnd) {
            new.selection
        } else {
            TextRange(editStart + inserted.length)
        }
        return EditResult(
            TextFieldValue(
                resultText,
                clampSelection(
                    text = resultText,
                    selection = selection,
                    mentions = updatedMentions,
                    protectedRanges = updatedProtectedRanges
                )
            ),
            updatedMentions,
            inserted,
            editStart
        )
    }

    fun replaceRange(
        text: String,
        mentions: List<MentionToken>,
        selection: TextRange,
        replacement: String
    ): ReplaceResult {
        val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
        val end = maxOf(selection.start, selection.end).coerceIn(start, text.length)
        val currentMentions = validMentions(text, mentions)
        val updatedMentions = transformMentions(
            mentions = currentMentions,
            editStart = start,
            editEnd = end,
            insertedLength = replacement.length
        )
        val resultText = text.substring(0, start) + replacement + text.substring(end)
        return ReplaceResult(
            text = resultText,
            mentions = updatedMentions,
            selection = TextRange(start + replacement.length)
        )
    }

    private fun transformMentions(
        mentions: List<MentionToken>,
        editStart: Int,
        editEnd: Int,
        insertedLength: Int
    ): List<MentionToken> {
        val offset = insertedLength - (editEnd - editStart)
        return mentions.mapNotNull { mention ->
            when {
                mention.endExclusive <= editStart -> mention
                mention.start >= editEnd -> mention.copy(
                    start = mention.start + offset,
                    endExclusive = mention.endExclusive + offset
                )
                else -> null
            }
        }.sortedBy { it.start }
    }

    private fun transformRanges(
        ranges: List<TextRange>,
        editStart: Int,
        editEnd: Int,
        insertedLength: Int
    ): List<TextRange> {
        val offset = insertedLength - (editEnd - editStart)
        return ranges.mapNotNull { range ->
            when {
                range.end <= editStart -> range
                range.start >= editEnd -> TextRange(
                    start = range.start + offset,
                    end = range.end + offset
                )
                else -> null
            }
        }.sortedBy { it.start }
    }

    private fun validProtectedRanges(text: String, ranges: List<TextRange>): List<TextRange> {
        return ranges.mapNotNull { range ->
            val start = minOf(range.start, range.end)
            val end = maxOf(range.start, range.end)
            if (start >= 0 && end <= text.length && start < end) {
                TextRange(start, end)
            } else {
                null
            }
        }.distinct().sortedBy { it.start }
    }

    private fun isValidTextEdit(oldText: String, newText: String, edit: TextEdit): Boolean {
        if (edit.start < 0 || edit.beforeCount < 0 || edit.afterCount < 0) return false
        val oldEnd = edit.start + edit.beforeCount
        val newEnd = edit.start + edit.afterCount
        if (oldEnd > oldText.length || newEnd > newText.length) return false
        if (oldText.length - edit.beforeCount + edit.afterCount != newText.length) return false
        if (!oldText.regionMatches(0, newText, 0, edit.start)) return false
        val suffixLength = oldText.length - oldEnd
        return oldText.regionMatches(oldEnd, newText, newEnd, suffixLength)
    }

    private fun clampSelection(
        text: String,
        selection: TextRange,
        mentions: List<MentionToken>,
        protectedRanges: List<TextRange> = emptyList()
    ): TextRange {
        val validMentions = validMentions(text, mentions)
        val atomicRanges = (
            validMentions.map { TextRange(it.start, it.endExclusive) } +
                validProtectedRanges(text, protectedRanges)
            ).sortedBy { it.start }
        if (atomicRanges.isEmpty()) return selection

        fun clampPos(p: Int): Int {
            for (range in atomicRanges) {
                if (p > range.start && p < range.end) {
                    return if (p - range.start < range.end - p) {
                        range.start
                    } else {
                        range.end
                    }
                }
            }
            return p
        }
        return TextRange(clampPos(selection.start), clampPos(selection.end))
    }
}
