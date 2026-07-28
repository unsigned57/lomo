package com.lomo.app.feature.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.usecase.ObserveDirectWorkspaceRootUseCase
import com.lomo.domain.usecase.RemoteSyncConflictDialogUseCase
import com.lomo.domain.usecase.SyncProviderRegistry
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Hosts review-state observation for Sync Inbox and remote conflict polling from Rust.
 *
 * Remote providers no longer emit ConflictDetected via deleted engines; open remote conflicts
 * are loaded from [RemoteSyncConflictDialogUseCase] when the Direct workspace root is known.
 */
class SyncConflictStateViewModel(
    syncProviderRegistry: SyncProviderRegistry,
    private val remoteSyncConflictDialogUseCase: RemoteSyncConflictDialogUseCase,
    observeDirectWorkspaceRootUseCase: ObserveDirectWorkspaceRootUseCase,
) : ViewModel() {
    val syncStates: StateFlow<ImmutableMap<SyncBackendType, UnifiedSyncState>> =
        combine(
            ReviewSyncProviders.map { provider ->
                syncProviderRegistry
                    .get(provider)
                    ?.syncState()
                    ?.map { state -> provider to state }
                    ?: flowOf(provider to UnifiedSyncState.Idle)
            },
        ) { entries -> entries.toMap().toImmutableMap() }
            .stateIn(
                scope = viewModelScope,
                started = appWhileSubscribed(),
                initialValue = ReviewSyncProviders.associateWith { UnifiedSyncState.Idle }.toImmutableMap(),
            )

    val workspaceRoot: StateFlow<String?> =
        observeDirectWorkspaceRootUseCase
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    fun loadRemoteOpenSession(workspaceRoot: String): RemoteSyncConflictDialogUseCase.OpenSession? =
        remoteSyncConflictDialogUseCase.loadOpenSession(workspaceRoot)
}

/** Providers that may still surface ReviewRequired (Sync Inbox independent of remote kernel). */
internal val ReviewSyncProviders: ImmutableSet<SyncBackendType> =
    persistentSetOf(SyncBackendType.INBOX)
