package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.repository.AppUpdateDownloadRepository
import kotlinx.coroutines.flow.Flow

class DownloadAndInstallAppUpdateUseCase(
    private val appUpdateDownloadRepository: AppUpdateDownloadRepository,
) {
    operator fun invoke(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState> =
        appUpdateDownloadRepository.downloadAndInstall(updateInfo)
}
