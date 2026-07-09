package com.lomo.domain.usecase

import com.lomo.domain.repository.AppRuntimeInfoRepository

class GetCurrentAppVersionUseCase(
    private val appRuntimeInfoRepository: AppRuntimeInfoRepository,
) {
    suspend operator fun invoke(): String = appRuntimeInfoRepository.getCurrentVersionName()
}
