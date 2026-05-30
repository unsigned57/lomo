package com.lomo.app.feature.settings

import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class RemoteSyncSettingsCoordinator<RawSyncState>(
    enabled: StateFlow<Boolean>,
    autoSyncEnabled: StateFlow<Boolean>,
    autoSyncInterval: StateFlow<String>,
    syncOnRefreshEnabled: StateFlow<Boolean>,
    lastSyncTime: StateFlow<Long>,
    rawSyncState: Flow<RawSyncState>,
    mapToUnifiedSyncState: (RawSyncState) -> UnifiedSyncState,
    private val updateEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    private val updateAutoSyncEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    private val updateAutoSyncIntervalAction: suspend (String) -> SettingsOperationError?,
    private val updateSyncOnRefreshEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    private val triggerSyncNowAction: suspend () -> SettingsOperationError?,
    private val testConnectionAction: suspend () -> SettingsOperationError?,
) {
    val enabled: StateFlow<Boolean> = enabled
    val autoSyncEnabled: StateFlow<Boolean> = autoSyncEnabled
    val autoSyncInterval: StateFlow<String> = autoSyncInterval
    val syncOnRefreshEnabled: StateFlow<Boolean> = syncOnRefreshEnabled
    val lastSyncTime: StateFlow<Long> = lastSyncTime
    val syncState: Flow<UnifiedSyncState> = rawSyncState.map(mapToUnifiedSyncState)

    suspend fun updateEnabled(value: Boolean): SettingsOperationError? = updateEnabledAction(value)

    suspend fun updateAutoSyncEnabled(value: Boolean): SettingsOperationError? = updateAutoSyncEnabledAction(value)

    suspend fun updateAutoSyncInterval(value: String): SettingsOperationError? = updateAutoSyncIntervalAction(value)

    suspend fun updateSyncOnRefreshEnabled(value: Boolean): SettingsOperationError? =
        updateSyncOnRefreshEnabledAction(value)

    suspend fun triggerSyncNow(): SettingsOperationError? = triggerSyncNowAction()

    suspend fun testConnection(): SettingsOperationError? = testConnectionAction()
}
