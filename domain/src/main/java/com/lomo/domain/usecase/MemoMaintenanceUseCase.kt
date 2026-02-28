package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import javax.inject.Inject

/**
 * Consolidates memo maintenance commands so callers can depend on one domain entrypoint.
 *
 * Compatibility constructors keep existing thin wrappers usable in tests/manual wiring.
 */
class MemoMaintenanceUseCase private constructor(
    private val deleteAction: suspend (Memo) -> Unit,
    private val refreshAction: suspend () -> Unit,
) {
    @Inject
    constructor(
        repository: MemoRepository,
        syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ) : this(
            deleteAction = { memo -> repository.deleteMemo(memo) },
            refreshAction = { syncAndRebuildUseCase(forceSync = false) },
        )

    constructor(repository: MemoRepository) : this(
        deleteAction = { memo -> repository.deleteMemo(memo) },
        refreshAction = {},
    )

    constructor(syncAndRebuildUseCase: SyncAndRebuildUseCase) : this(
        deleteAction = { _ -> },
        refreshAction = { syncAndRebuildUseCase(forceSync = false) },
    )

    suspend fun deleteMemo(memo: Memo) {
        deleteAction(memo)
    }

    suspend fun refreshMemos() {
        refreshAction()
    }
}
