package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateCheckOutcome
import com.lomo.domain.model.AppUpdateFetchException
import com.lomo.domain.repository.AppUpdateRepository

class GetLatestAppReleaseUseCase(
    private val appUpdateRepository: AppUpdateRepository,
) {
    suspend operator fun invoke(): AppUpdateCheckOutcome =
        try {
            AppUpdateCheckOutcome.Available(appUpdateRepository.fetchLatestRelease().toAppUpdateInfo())
        } catch (error: AppUpdateFetchException) {
            AppUpdateCheckOutcome.Failed(error.failure)
        }
}
