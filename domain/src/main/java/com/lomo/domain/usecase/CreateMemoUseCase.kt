package com.lomo.domain.usecase

import com.lomo.domain.repository.MemoRepository

class CreateMemoUseCase
(
        private val memoRepository: MemoRepository,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val validator: ValidateMemoContentUseCase,
    ) {
        suspend operator fun invoke(
            content: String,
            timestampMillis: Long = System.currentTimeMillis(),
        ) {
            checkNotNull(initializeWorkspaceUseCase.currentRootLocation()) {
                "Please select a folder first"
            }
            validator.requireValidForCreate(content)
            memoRepository.saveMemo(content, timestampMillis)
        }
    }
