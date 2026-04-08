package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.repository.AppUpdateRepository

class GetLatestAppReleaseUseCase(
    private val appUpdateRepository: AppUpdateRepository,
) {
    suspend operator fun invoke(): AppUpdateInfo? =
        appUpdateRepository.fetchLatestRelease()?.toAppUpdateInfo()
}
