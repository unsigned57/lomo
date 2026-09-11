package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.WorkspaceMutationLease

/**
 * Creates one memo as a single admitted workspace mutation.
 *
 * Root selection, validation and save share one admission so a workspace switch cannot land between
 * validating against one workspace and saving into another. The lease is the only write gate here;
 * readiness is not re-checked separately, so this use case cannot drift from the owning boundary.
 */
open class CreateMemoUseCase(
    private val memoRepository: MemoMutationRepository,
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
    private val validator: ValidateMemoContentUseCase,
    private val workspaceMutationLease: WorkspaceMutationLease,
) {
    open suspend operator fun invoke(
        content: String,
        timestampMillis: Long = System.currentTimeMillis(),
        geoLocation: String? = null,
    ): Memo =
        workspaceMutationLease.withWrite {
            checkNotNull(initializeWorkspaceUseCase.currentRootLocation()) {
                "Please select a folder first"
            }
            validator.requireValidForCreate(content)
            memoRepository.saveMemo(content, timestampMillis, geoLocation)
        }
}
