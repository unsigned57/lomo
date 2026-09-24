package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateCheckOutcome
import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first

class CheckStartupAppUpdateUseCase(
    private val preferencesRepository: PreferencesRepository,
    private val appUpdateRepository: AppUpdateRepository,
    private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
) {
    /**
     * Startup checks stay silent by contract, but the returned outcome keeps the full result —
     * including a typed failure — so callers can keep diagnostics instead of swallowing them.
     */
    suspend operator fun invoke(): AppUpdateCheckOutcome {
        if (!preferencesRepository.isCheckUpdatesOnStartupEnabled().first()) {
            return AppUpdateCheckOutcome.UpToDate
        }
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
