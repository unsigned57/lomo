package com.lomo.domain.usecase

class RefreshMemosUseCase
(
        private val memoMaintenanceUseCase: MemoMaintenanceUseCase,
    ) {
        constructor(syncAndRebuildUseCase: SyncAndRebuildUseCase) : this(
            MemoMaintenanceUseCase(syncAndRebuildUseCase),
        )

        suspend operator fun invoke() {
            memoMaintenanceUseCase.refreshMemos()
        }
    }
