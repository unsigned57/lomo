package com.lomo.app.feature.settings

import com.lomo.domain.model.StoredCredentialStatus
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

sealed interface RemoteProviderConnectionTestState {
    data object Idle : RemoteProviderConnectionTestState

    data object Testing : RemoteProviderConnectionTestState

    data class Success(
        val message: String,
    ) : RemoteProviderConnectionTestState

    data class Error(
        val provider: SyncBackendType,
        val providerCode: String?,
        val detail: String?,
    ) : RemoteProviderConnectionTestState
}

data class RemoteProviderCredentialFieldState(
    val field: RemoteProviderCredentialField,
    val status: StoredCredentialStatus,
)

interface RemoteProviderSettingsActionTarget {
    suspend fun updateEnabled(value: Boolean): SettingsOperationError?

    suspend fun updateAutoSyncEnabled(value: Boolean): SettingsOperationError?

    suspend fun updateAutoSyncInterval(value: String): SettingsOperationError?

    suspend fun updateSyncOnRefreshEnabled(value: Boolean): SettingsOperationError?

    suspend fun triggerSyncNow(): SettingsOperationError?

    suspend fun testConnection(): SettingsOperationError?
}

data class RemoteProviderSettingsModel(
    val provider: SyncBackendType,
    val enabled: Boolean,
    val autoSyncEnabled: Boolean,
    val autoSyncInterval: String,
    val syncOnRefreshEnabled: Boolean,
    val lastSyncTime: Long,
    val syncState: UnifiedSyncState,
    val connectionTestState: RemoteProviderConnectionTestState,
    val credentialFields: List<RemoteProviderCredentialFieldState> = emptyList(),
)

class ProviderSettingsController<RawSyncState>(
    private val provider: SyncBackendType,
    scope: CoroutineScope,
    enabled: StateFlow<Boolean>,
    autoSyncEnabled: StateFlow<Boolean>,
    autoSyncInterval: StateFlow<String>,
    syncOnRefreshEnabled: StateFlow<Boolean>,
    lastSyncTime: StateFlow<Long>,
    credentialFields: StateFlow<List<RemoteProviderCredentialFieldState>>,
    rawSyncState: Flow<RawSyncState>,
    mapToUnifiedSyncState: (RawSyncState) -> UnifiedSyncState,
    private val updateEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    private val updateAutoSyncEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    private val updateAutoSyncIntervalAction: suspend (String) -> SettingsOperationError?,
    private val updateSyncOnRefreshEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    private val triggerSyncNowAction: suspend () -> SettingsOperationError?,
    private val testConnectionAction: suspend () -> RemoteProviderConnectionTestState,
    private val mapConnectionFailure: (Throwable) -> RemoteProviderConnectionTestState.Error,
) : RemoteProviderSettingsActionTarget {
    private data class BehaviorState(
        val enabled: Boolean,
        val autoSyncEnabled: Boolean,
        val autoSyncInterval: String,
        val syncOnRefreshEnabled: Boolean,
    )

    private val _connectionTestState =
        MutableStateFlow<RemoteProviderConnectionTestState>(RemoteProviderConnectionTestState.Idle)
    val connectionTestState: StateFlow<RemoteProviderConnectionTestState> = _connectionTestState.asStateFlow()

    val syncState: StateFlow<UnifiedSyncState> =
        rawSyncState
            .map(mapToUnifiedSyncState)
            .settingsStateIn(scope, UnifiedSyncState.Idle)

    private val behaviorState: StateFlow<BehaviorState> =
        combine(
            enabled,
            autoSyncEnabled,
            autoSyncInterval,
            syncOnRefreshEnabled,
        ) { enabledValue, autoSyncEnabledValue, autoSyncIntervalValue, syncOnRefreshEnabledValue ->
            BehaviorState(
                enabled = enabledValue,
                autoSyncEnabled = autoSyncEnabledValue,
                autoSyncInterval = autoSyncIntervalValue,
                syncOnRefreshEnabled = syncOnRefreshEnabledValue,
            )
        }.settingsStateIn(
            scope = scope,
            initialValue =
                BehaviorState(
                    enabled = enabled.value,
                    autoSyncEnabled = autoSyncEnabled.value,
                    autoSyncInterval = autoSyncInterval.value,
                    syncOnRefreshEnabled = syncOnRefreshEnabled.value,
                ),
        )

    val model: StateFlow<RemoteProviderSettingsModel> =
        combine(
            behaviorState,
            lastSyncTime,
            syncState,
            credentialFields,
            connectionTestState,
        ) { behavior, lastSyncTimeValue, syncStateValue, credentialFieldValues, connectionTestStateValue ->
            RemoteProviderSettingsModel(
                provider = provider,
                enabled = behavior.enabled,
                autoSyncEnabled = behavior.autoSyncEnabled,
                autoSyncInterval = behavior.autoSyncInterval,
                syncOnRefreshEnabled = behavior.syncOnRefreshEnabled,
                lastSyncTime = lastSyncTimeValue,
                syncState = syncStateValue,
                connectionTestState = connectionTestStateValue,
                credentialFields = credentialFieldValues,
            )
        }.settingsStateIn(
            scope = scope,
            initialValue =
                RemoteProviderSettingsModel(
                    provider = provider,
                    enabled = behaviorState.value.enabled,
                    autoSyncEnabled = behaviorState.value.autoSyncEnabled,
                    autoSyncInterval = behaviorState.value.autoSyncInterval,
                    syncOnRefreshEnabled = behaviorState.value.syncOnRefreshEnabled,
                    lastSyncTime = lastSyncTime.value,
                    syncState = syncState.value,
                    connectionTestState = connectionTestState.value,
                    credentialFields = credentialFields.value,
                ),
        )

    override suspend fun updateEnabled(value: Boolean): SettingsOperationError? = updateEnabledAction(value)

    override suspend fun updateAutoSyncEnabled(value: Boolean): SettingsOperationError? =
        updateAutoSyncEnabledAction(value)

    override suspend fun updateAutoSyncInterval(value: String): SettingsOperationError? =
        updateAutoSyncIntervalAction(value)

    override suspend fun updateSyncOnRefreshEnabled(value: Boolean): SettingsOperationError? =
        updateSyncOnRefreshEnabledAction(value)

    override suspend fun triggerSyncNow(): SettingsOperationError? = triggerSyncNowAction()

    override suspend fun testConnection(): SettingsOperationError? {
        _connectionTestState.value = RemoteProviderConnectionTestState.Testing
        _connectionTestState.value =
            runCatching { testConnectionAction() }
                .getOrElse { throwable ->
                    if (throwable is CancellationException) {
                        _connectionTestState.value = RemoteProviderConnectionTestState.Idle
                        throw throwable
                    }
                    mapConnectionFailure(throwable)
                }
        return null
    }

    fun resetConnectionTestState() {
        _connectionTestState.value = RemoteProviderConnectionTestState.Idle
    }
}

typealias RemoteProviderSettingsController<RawSyncState> = ProviderSettingsController<RawSyncState>

fun RemoteProviderSettingsModel.credentialStatus(field: RemoteProviderCredentialField): StoredCredentialStatus =
    credentialFields
        .firstOrNull { state -> state.field == field }
        ?.status
        ?: StoredCredentialStatus.Missing
