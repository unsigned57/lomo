package com.lomo.app.feature.common

import com.lomo.app.di.AppScope
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import com.lomo.domain.repository.CustomFontStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigStateProvider
    @Inject
    constructor(
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
        private val appPreferencesSnapshotRepository: AppPreferencesSnapshotRepository,
        private val customFontStore: CustomFontStore,
        @AppScope private val appScope: CoroutineScope,
    ) {
        val rootDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .rootDirectory()
                .stateIn(appScope, appWhileSubscribed(), null)

        val imageDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .imageDirectory()
                .stateIn(appScope, appWhileSubscribed(), null)

        val voiceDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .voiceDirectory()
                .stateIn(appScope, appWhileSubscribed(), null)

        val appPreferences: StateFlow<AppPreferencesState> =
            appPreferencesSnapshotRepository
                .observeAppPreferences(customFontStore)
                .stateIn(appScope, appWhileSubscribed(), AppPreferencesState.defaults())

        val appLockEnabled: StateFlow<Boolean?> =
            appConfigUiCoordinator
                .appLockEnabled()
                .stateIn(appScope, appWhileSubscribed(), null)

        suspend fun currentImageDirectory(): String? = appConfigUiCoordinator.currentImageDirectory()
    }
