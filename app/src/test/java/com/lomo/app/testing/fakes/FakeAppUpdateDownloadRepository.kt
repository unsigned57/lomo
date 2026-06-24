package com.lomo.app.testing.fakes

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallAttempt
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.repository.AppUpdateDownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

class FakeAppUpdateDownloadRepository : AppUpdateDownloadRepository {
    var downloadFlow: Flow<AppUpdateInstallState>? = null
    val installAttempt = MutableStateFlow<AppUpdateInstallAttempt?>(null)
    var cancelCurrentDownloadCalledCount = 0
    var downloadAndInstallCalledCount = 0
    var lastUpdateInfo: AppUpdateInfo? = null

    override fun observeInstallAttempt(): Flow<AppUpdateInstallAttempt?> = installAttempt

    override fun downloadAndInstall(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState> {
        downloadAndInstallCalledCount++
        lastUpdateInfo = updateInfo
        return downloadFlow ?: flow {}
    }

    override fun cancelCurrentDownload() {
        cancelCurrentDownloadCalledCount++
    }
}
