package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.WidgetRepository
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import javax.inject.Inject

/**
 * Encapsulates memo mutation workflows used by Main screen.
 */
class MainMemoMutator
    @Inject
    constructor(
        private val createMemoUseCase: CreateMemoUseCase,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoUseCase: UpdateMemoUseCase,
        private val widgetRepository: WidgetRepository,
        private val textProcessor: com.lomo.data.util.MemoTextProcessor,
    ) {
        suspend fun addMemo(content: String) {
            createMemoUseCase(content)
            widgetRepository.updateAllWidgets()
        }

        suspend fun deleteMemo(memo: Memo) {
            deleteMemoUseCase(memo)
            widgetRepository.updateAllWidgets()
        }

        suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            updateMemoUseCase(memo, newContent)
            widgetRepository.updateAllWidgets()
        }

        suspend fun toggleCheckbox(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ) {
            val newContent = textProcessor.toggleCheckbox(memo.content, lineIndex, checked)
            if (newContent != memo.content) {
                updateMemoUseCase(memo, newContent)
            }
        }
    }
