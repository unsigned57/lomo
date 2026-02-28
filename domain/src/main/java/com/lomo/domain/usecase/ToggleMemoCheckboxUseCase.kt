package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.validation.MemoContentValidator
import javax.inject.Inject

class ToggleMemoCheckboxUseCase
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val validator: MemoContentValidator,
    ) {
        suspend operator fun invoke(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ): Boolean {
            val newContent = toggleCheckboxLine(memo.content, lineIndex, checked)
            if (newContent == memo.content) return false

            validator.validateForUpdate(newContent)
            repository.updateMemo(memo, newContent)
            return true
        }

        private fun toggleCheckboxLine(
            content: String,
            lineIndex: Int,
            checked: Boolean,
        ): String {
            if (lineIndex < 0) return content

            val lines = content.split('\n').toMutableList()
            if (lineIndex >= lines.size) return content

            val originalLine = lines[lineIndex]
            val match = CHECKBOX_LINE_PREFIX.find(originalLine) ?: return content
            val currentChecked = match.groupValues[2] != " "
            if (currentChecked == checked) return content

            val updatedLine = match.groupValues[1] + (if (checked) "x" else " ") + match.groupValues[3]
            lines[lineIndex] = updatedLine
            return lines.joinToString(separator = "\n")
        }

        companion object {
            // Match Markdown task list marker strictly at line start (optional indentation).
            private val CHECKBOX_LINE_PREFIX =
                Regex("""^(\s*(?:[-+*]|\d+[.)])\s+\[)([ xX])(\].*)$""")
        }
    }
