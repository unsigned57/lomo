package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first

class CheckStartupAppUpdateUseCase
(
        private val preferencesRepository: PreferencesRepository,
        private val appUpdateRepository: AppUpdateRepository,
        private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
    ) {
        suspend operator fun invoke(): AppUpdateInfo? {
            if (!preferencesRepository.isCheckUpdatesOnStartupEnabled().first()) {
                return null
            }
            val latestRelease = appUpdateRepository.fetchLatestRelease() ?: return null
            return evaluateAppUpdate(
                release = latestRelease,
                currentVersionName = appRuntimeInfoRepository.getCurrentVersionName(),
            )
        }
    }
