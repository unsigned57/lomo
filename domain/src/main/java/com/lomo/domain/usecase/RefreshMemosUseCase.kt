package com.lomo.domain.usecase


class RefreshMemosUseCase
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
