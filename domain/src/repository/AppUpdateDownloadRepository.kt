package com.lomo.domain.repository

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallAttempt
import com.lomo.domain.model.AppUpdateInstallState
import kotlinx.coroutines.flow.Flow

interface AppUpdateDownloadRepository {
    fun observeInstallAttempt(): Flow<AppUpdateInstallAttempt?>

    fun downloadAndInstall(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState>

    fun cancelCurrentDownload()
}
