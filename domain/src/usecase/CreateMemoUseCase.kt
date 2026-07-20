package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.requireWritable
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.WriteFreezeRepository

open class CreateMemoUseCase(
    private val memoRepository: MemoMutationRepository,
    private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
    private val validator: ValidateMemoContentUseCase,
    private val engineReadinessRepository: EngineReadinessRepository,
    private val writeFreezeRepository: WriteFreezeRepository,
) {
    open suspend operator fun invoke(
        content: String,
        timestampMillis: Long = System.currentTimeMillis(),
        geoLocation: String? = null,
    ): Memo {
        // Global write hard gate: Ready + no freeze is the only writable authority.
        engineReadinessRepository.readiness.value.requireWritable(
            writeFrozen = writeFreezeRepository.isFrozen.value,
        )
        checkNotNull(initializeWorkspaceUseCase.currentRootLocation()) {
            "Please select a folder first"
        }
        validator.requireValidForCreate(content)
        return memoRepository.saveMemo(content, timestampMillis, geoLocation)
    }
}
