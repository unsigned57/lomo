package com.lomo.app.feature.common

import com.lomo.domain.repository.AppConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppConfigUiCoordinator(private val appConfigRepository: AppConfigRepository) {
    fun appLockEnabled(): Flow<Boolean?> = appConfigRepository
        .isAppLockEnabled()
        .map<Boolean, Boolean?> { it }

    fun sidebarTagOrder(): Flow<List<String>> = appConfigRepository.getSidebarTagOrder()

    suspend fun updateInputToolbarToolOrder(order: List<String>) {
        appConfigRepository.updateInputToolbarToolOrder(order)
    }

    suspend fun updateSidebarTagOrder(order: List<String>) {
        appConfigRepository.updateSidebarTagOrder(order)
    }
}
