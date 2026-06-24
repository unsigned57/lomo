package com.lomo.app.feature.conflict

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.usecase.SyncProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SyncConflictStateViewModel
    @Inject
    constructor(
        syncProviderRegistry: SyncProviderRegistry,
    ) : ViewModel() {
        val syncStates: StateFlow<ImmutableMap<SyncBackendType, UnifiedSyncState>> =
            combine(
                RemoteSyncConflictProviders.map { provider ->
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
                    initialValue = RemoteSyncConflictProviders.associateWith { UnifiedSyncState.Idle }.toImmutableMap(),
                )
    }
