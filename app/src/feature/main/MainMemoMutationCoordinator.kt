package com.lomo.app.feature.main

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase


class MainMemoMutationCoordinator(
    private val deleteMemoUseCase: DeleteMemoUseCase,
    private val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
    private val appWidgetRepository: AppWidgetRepository,
) {
        suspend fun deleteMemo(memo: Memo) {
            deleteMemoUseCase(memo)
            appWidgetRepository.updateAllWidgets()
        }

        suspend fun toggleCheckboxLineAndUpdate(
            memo: Memo,
            lineIndex: Int,
            checked: Boolean,
        ): String? {
            val updatedContent = toggleMemoCheckboxUseCase(memo, lineIndex, checked)
            if (updatedContent != null) {
                appWidgetRepository.updateAllWidgets()
            }
            return updatedContent
        }
    }
