package com.lomo.app.feature.common

import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.CustomFontHost
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.domain.model.PreferencesCorruptionNotice
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.PreferencesHealthRepository
import com.lomo.domain.usecase.ObserveWorkspaceSessionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AppConfigStateProvider(
    appConfigUiCoordinator: AppConfigUiCoordinator,
    appPreferencesSnapshotRepository: AppPreferencesSnapshotRepository,
    private val customFontStore: CustomFontStore,
    private val customFontHost: CustomFontHost,
    private val preferencesHealthRepository: PreferencesHealthRepository,
    private val appScope: CoroutineScope,
    workspaceSession: ObserveWorkspaceSessionUseCase,
) {
    private val workspaceMount = workspaceSession.mount
    val rootDirectory: StateFlow<String?> =
        workspaceMount.map { it.location?.raw }.stateIn(appScope, appWhileSubscribed(), null)

    val imageDirectory: StateFlow<String?> =
        workspaceMount.map { it.attachments.images?.raw }.stateIn(appScope, appWhileSubscribed(), null)

    val voiceDirectory: StateFlow<String?> =
        workspaceMount.map { it.attachments.recordings?.raw }.stateIn(
            appScope,
            appWhileSubscribed(),
            null,
        )

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
}
