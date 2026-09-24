package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateCheckOutcome
import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository

class CheckAppUpdateUseCase(
    private val appUpdateRepository: AppUpdateRepository,
    private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
) {
    suspend operator fun invoke(): AppUpdateCheckOutcome {
        val latestRelease =
            try {
                appUpdateRepository.fetchLatestRelease()
            } catch (error: AppUpdateFetchException) {
                return AppUpdateCheckOutcome.Failed(error.failure)
            }
        val update =
            evaluateAppUpdate(
                release = latestRelease,
                currentVersionName = appRuntimeInfoRepository.getCurrentVersionName(),
                currentVersionCode = appRuntimeInfoRepository.getCurrentVersionCode(),
            ) ?: return AppUpdateCheckOutcome.UpToDate
        return AppUpdateCheckOutcome.Available(update)
    }
}
