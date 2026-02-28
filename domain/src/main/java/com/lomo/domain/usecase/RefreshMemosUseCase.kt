package com.lomo.domain.usecase

import javax.inject.Inject

class RefreshMemosUseCase
    @Inject
    constructor(
        private val syncAndRebuildUseCase: SyncAndRebuildUseCase,
    ) {
        suspend operator fun invoke() {
            syncAndRebuildUseCase(forceSync = false)
        }
    }
