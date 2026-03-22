package com.lomo.app.feature.common

import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.domain.model.StorageArea
import com.lomo.domain.repository.AppConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AppConfigUiCoordinator
    @Inject
    constructor(
        private val appConfigRepository: AppConfigRepository,
    ) {
        fun rootDirectory(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.ROOT)
                .map { it?.raw }

        fun imageDirectory(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.IMAGE)
                .map { it?.raw }

        fun voiceDirectory(): Flow<String?> =
            appConfigRepository
                .observeLocation(StorageArea.VOICE)
                .map { it?.raw }

        fun appPreferences(): Flow<AppPreferencesState> = appConfigRepository.observeAppPreferences()

        fun appLockEnabled(): Flow<Boolean?> =
            appConfigRepository
                .isAppLockEnabled()
                .map<Boolean, Boolean?> { it }
    }
