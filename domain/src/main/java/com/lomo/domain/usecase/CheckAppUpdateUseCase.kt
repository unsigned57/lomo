package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository

class CheckAppUpdateUseCase(
    private val appUpdateRepository: AppUpdateRepository,
    private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
) {
    suspend operator fun invoke(): AppUpdateInfo? {
        val latestRelease = appUpdateRepository.fetchLatestRelease() ?: return null
        val currentVersionName = appRuntimeInfoRepository.getCurrentVersionName()
        return evaluateAppUpdate(
            release = latestRelease,
            currentVersionName = currentVersionName,
        )
    }
}
