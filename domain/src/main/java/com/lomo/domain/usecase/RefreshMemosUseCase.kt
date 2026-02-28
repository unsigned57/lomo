package com.lomo.domain.usecase

import javax.inject.Inject

class RefreshMemosUseCase
    @Inject
    constructor(
        private val memoMaintenanceUseCase: MemoMaintenanceUseCase,
    ) {
        constructor(syncAndRebuildUseCase: SyncAndRebuildUseCase) : this(
            MemoMaintenanceUseCase(syncAndRebuildUseCase),
        )

        suspend operator fun invoke() {
            memoMaintenanceUseCase.refreshMemos()
        }
    }
