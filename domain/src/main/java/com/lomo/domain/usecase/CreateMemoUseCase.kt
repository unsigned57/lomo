package com.lomo.domain.usecase

import com.lomo.domain.repository.MemoMutationRepository

open class CreateMemoUseCase
(
        private val memoRepository: MemoMutationRepository,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val validator: ValidateMemoContentUseCase,
    ) {
        open suspend operator fun invoke(
            content: String,
            timestampMillis: Long = System.currentTimeMillis(),
            geoLocation: String? = null,
        ) {
            checkNotNull(initializeWorkspaceUseCase.currentRootLocation()) {
                "Please select a folder first"
            }
            validator.requireValidForCreate(content)
            memoRepository.saveMemo(content, timestampMillis, geoLocation)
        }
    }
