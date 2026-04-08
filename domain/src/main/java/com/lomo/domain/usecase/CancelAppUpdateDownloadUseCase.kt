package com.lomo.domain.usecase

import com.lomo.domain.repository.AppUpdateDownloadRepository

class CancelAppUpdateDownloadUseCase(
    private val appUpdateDownloadRepository: AppUpdateDownloadRepository,
) {
    operator fun invoke() {
        appUpdateDownloadRepository.cancelCurrentDownload()
    }
}
