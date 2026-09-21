package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase


class MainMemoMutationCoordinator(
    private val deleteMemoUseCase: DeleteMemoUseCase,
    private val toggleMemoCheckboxUseCase: ToggleMemoCheckboxUseCase,
) {
        suspend fun deleteMemo(
            memo: Memo,
            operationId: MemoOperationId,
        ) {
            deleteMemoUseCase(memo, operationId)
        }

        suspend fun toggleCheckboxLineAndUpdate(
            memo: Memo,
            actionSpan: MarkdownSourceSpan,
        ): String {
            val updatedContent = toggleMemoCheckboxUseCase(memo, actionSpan)
            return updatedContent
        }
    }
