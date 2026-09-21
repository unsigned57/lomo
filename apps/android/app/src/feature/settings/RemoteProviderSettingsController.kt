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

/** One provider's settings inputs: the observable state, the raw sync flow and the action callbacks. */
data class ProviderSettingsControllerDependencies<RawSyncState>(
    val provider: SyncBackendType,
    val scope: CoroutineScope,
    val enabled: StateFlow<Boolean>,
    val autoSyncEnabled: StateFlow<Boolean>,
    val autoSyncInterval: StateFlow<String>,
    val syncOnRefreshEnabled: StateFlow<Boolean>,
    val lastSyncTime: StateFlow<Long>,
    val credentialFields: StateFlow<List<RemoteProviderCredentialFieldState>>,
    val rawSyncState: Flow<RawSyncState>,
    val mapToUnifiedSyncState: (RawSyncState) -> UnifiedSyncState,
    val updateEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    val updateAutoSyncEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    val updateAutoSyncIntervalAction: suspend (String) -> SettingsOperationError?,
    val updateSyncOnRefreshEnabledAction: suspend (Boolean) -> SettingsOperationError?,
    val triggerSyncNowAction: suspend () -> SettingsOperationError?,
    val testConnectionAction: suspend () -> RemoteProviderConnectionTestState,
    val mapConnectionFailure: (Throwable) -> RemoteProviderConnectionTestState.Error,
)

class ProviderSettingsController<RawSyncState>(
    dependencies: ProviderSettingsControllerDependencies<RawSyncState>,
) : RemoteProviderSettingsActionTarget {
    private val provider = dependencies.provider
    private val scope = dependencies.scope
    private val enabled = dependencies.enabled
    private val autoSyncEnabled = dependencies.autoSyncEnabled
    private val autoSyncInterval = dependencies.autoSyncInterval
    private val syncOnRefreshEnabled = dependencies.syncOnRefreshEnabled
    private val lastSyncTime = dependencies.lastSyncTime
    private val credentialFields = dependencies.credentialFields
    private val rawSyncState = dependencies.rawSyncState
    private val mapToUnifiedSyncState = dependencies.mapToUnifiedSyncState
    private val updateEnabledAction = dependencies.updateEnabledAction
    private val updateAutoSyncEnabledAction = dependencies.updateAutoSyncEnabledAction
    private val updateAutoSyncIntervalAction = dependencies.updateAutoSyncIntervalAction
    private val updateSyncOnRefreshEnabledAction = dependencies.updateSyncOnRefreshEnabledAction
    private val triggerSyncNowAction = dependencies.triggerSyncNowAction
    private val testConnectionAction = dependencies.testConnectionAction
    private val mapConnectionFailure = dependencies.mapConnectionFailure
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
        var settled = false
        try {
            _connectionTestState.value = testConnectionAction()
            settled = true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Exception) {
            _connectionTestState.value = mapConnectionFailure(throwable)
            settled = true
        } finally {
            if (!settled) {
                _connectionTestState.value = RemoteProviderConnectionTestState.Idle
            }
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
