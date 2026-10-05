package com.lomo.app.feature.common


import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.CustomFontHost
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.domain.model.PreferencesCorruptionNotice
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.PreferencesHealthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn


class AppConfigStateProvider(
    private val appConfigUiCoordinator: AppConfigUiCoordinator,
    appPreferencesSnapshotRepository: AppPreferencesSnapshotRepository,
    private val customFontStore: CustomFontStore,
    private val customFontHost: CustomFontHost,
    private val preferencesHealthRepository: PreferencesHealthRepository,
    private val appScope: CoroutineScope,
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
                .observeAppPreferences(customFontStore, customFontHost)
                .stateIn(appScope, appWhileSubscribed(), AppPreferencesState.defaults())

        val preferencesCorruptionNotice: StateFlow<PreferencesCorruptionNotice?> =
            preferencesHealthRepository.corruptionNotice

        suspend fun acknowledgeCorruptionNotice() = preferencesHealthRepository.acknowledgeCorruptionNotice()

        val appLockEnabled: StateFlow<Boolean?> =
            appConfigUiCoordinator
                .appLockEnabled()
                .stateIn(appScope, appWhileSubscribed(), null)

        suspend fun currentImageDirectory(): String? = appConfigUiCoordinator.currentImageDirectory()
    }
