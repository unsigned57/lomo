package com.lomo.domain.usecase

import com.lomo.domain.repository.AppRuntimeInfoRepository

class GetCurrentAppBuildVersionUseCase(
    private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
) {
    suspend operator fun invoke(): String {
        val versionName = appRuntimeInfoRepository.getCurrentVersionName()
        require(versionName.isNotBlank()) {
            "Android app versionName must be available during startup maintenance."
        }
        val versionCode =
            requireNotNull(appRuntimeInfoRepository.getCurrentVersionCode()) {
                "Android app versionCode must be available during startup maintenance."
            }
        return "$versionName($versionCode)"
    }
}
