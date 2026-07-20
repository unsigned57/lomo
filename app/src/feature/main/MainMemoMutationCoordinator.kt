package com.lomo.app.feature.main

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownSourceSpan
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
            actionSpan: MarkdownSourceSpan,
        ): String {
            val updatedContent = toggleMemoCheckboxUseCase(memo, actionSpan)
            appWidgetRepository.updateAllWidgets()
            return updatedContent
        }
    }
