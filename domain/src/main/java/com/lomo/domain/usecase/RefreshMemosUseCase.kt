package com.lomo.domain.usecase

class RefreshMemosUseCase
(
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ) {
        suspend operator fun invoke() {
            syncAndRebuildUseCase(forceSync = false)
        }
    }
