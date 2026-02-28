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

            val currentMark = if (checked) "- [ ]" else "- [x]"
            val targetMark = if (checked) "- [x]" else "- [ ]"

            val lines = content.split('\n').toMutableList()
            if (lineIndex >= lines.size) return content

            val originalLine = lines[lineIndex]
            val updatedLine = originalLine.replaceFirst(currentMark, targetMark)
            if (updatedLine == originalLine) return content

            lines[lineIndex] = updatedLine
            return lines.joinToString(separator = "\n")
        }
    }
