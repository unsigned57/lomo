package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.usecase.ValidateMemoContentUseCase

class ToggleMemoCheckboxUseCase
(
        private val repository: MemoMutationRepository,
        private val validator: ValidateMemoContentUseCase,
    ) {
        /**
         * Toggles the checkbox on [lineIndex] and persists the change.
         *
         * @return the updated memo content when a change was applied, or `null` when [lineIndex] is
         *   not a checkbox line or already in the requested state. Snapshot-backed collections use
         *   the returned content to apply an optimistic update without re-parsing the line.
         */
        suspend operator fun invoke(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ): String? {
            val newContent = toggleCheckboxLine(memo.content, lineIndex, checked)
            if (newContent == memo.content) return null

            validator.requireValidForUpdate(newContent)
            repository.updateMemo(memo, newContent)
            return newContent
        }

        private fun toggleCheckboxLine(
            content: String,
            lineIndex: Int,
            checked: Boolean,
        ): String {
            if (lineIndex < 0) return content

            val lines = splitByNewlinePreservingTrailing(content)
            val updatedContent =
                lines.getOrNull(lineIndex)
                    ?.let { originalLine -> buildUpdatedCheckboxLine(originalLine, checked) }
                    ?.let { updatedLine ->
                        lines[lineIndex] = updatedLine
                        lines.joinToString(separator = NEW_LINE_SEPARATOR)
                    }
            return updatedContent ?: content
        }

        private fun splitByNewlinePreservingTrailing(content: String): MutableList<String> {
            val lines = mutableListOf<String>()
            var startIndex = 0
            while (true) {
                val lineBreakIndex = content.indexOf('\n', startIndex)
                if (lineBreakIndex < 0) {
                    lines += content.substring(startIndex)
                    return lines
                }
                lines += content.substring(startIndex, lineBreakIndex)
                startIndex = lineBreakIndex + 1
                if (startIndex == content.length) {
                    lines += ""
                    return lines
                }
            }
        }

        private fun buildUpdatedCheckboxLine(
            originalLine: String,
            checked: Boolean,
        ): String? {
            val match = CHECKBOX_LINE_PREFIX.find(originalLine) ?: return null
            val currentChecked = match.groupValues[CHECK_STATE_GROUP_INDEX] != UNCHECKED_MARKER
            return if (currentChecked == checked) {
                null
            } else {
                buildString {
                    append(match.groupValues[PREFIX_GROUP_INDEX])
                    append(if (checked) CHECKED_MARKER else UNCHECKED_MARKER)
                    append(match.groupValues[SUFFIX_GROUP_INDEX])
                }
            }
        }

        companion object {
            // Match Markdown task list marker strictly at line start (optional indentation).
            private val CHECKBOX_LINE_PREFIX =
                Regex("""^(\s*(?:[-+*]|\d+[.)])\s+\[)([ xX])(\].*)$""")
            private const val PREFIX_GROUP_INDEX = 1
            private const val CHECK_STATE_GROUP_INDEX = 2
            private const val SUFFIX_GROUP_INDEX = 3
            private const val CHECKED_MARKER = "x"
            private const val UNCHECKED_MARKER = " "
            private const val NEW_LINE_SEPARATOR = "\n"
        }
    }
