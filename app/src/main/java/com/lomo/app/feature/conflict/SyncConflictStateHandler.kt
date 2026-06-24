package com.lomo.app.feature.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncState
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

internal val RemoteSyncConflictProviders: ImmutableSet<SyncBackendType> =
    persistentSetOf(
        SyncBackendType.GIT,
        SyncBackendType.WEBDAV,
        SyncBackendType.S3,
    )

@Composable
internal fun SyncConflictStateHost(
    syncStates: ImmutableMap<SyncBackendType, UnifiedSyncState>,
    providers: ImmutableSet<SyncBackendType>,
    controller: SyncConflictDialogController,
) {
    providers.forEach { provider ->
        SyncConflictStateHandler(
            syncState = syncStates[provider] ?: UnifiedSyncState.Idle,
            provider = provider,
            onShowConflictDialog = controller.onShowConflictDialog,
            onShowReviewDialog = controller.onShowReviewDialog,
        )
    }
}

@Composable
internal fun SyncConflictStateHandler(
    syncState: UnifiedSyncState,
    provider: SyncBackendType,
    onShowConflictDialog: (SyncConflictSet) -> Unit,
    onShowReviewDialog: (SyncReviewSession) -> Unit,
) {
    LaunchedEffect(syncState, provider) {
        consumeProviderSyncConflictState(
            syncState = syncState,
            provider = provider,
            onShowConflictDialog = onShowConflictDialog,
            onShowReviewDialog = onShowReviewDialog,
        )
    }
}

internal fun consumeSyncConflictState(
    syncState: UnifiedSyncState,
    providers: Set<SyncBackendType>,
    onShowConflictDialog: (SyncConflictSet) -> Unit,
    onShowReviewDialog: (SyncReviewSession) -> Unit,
) {
    providers.forEach { provider ->
        consumeProviderSyncConflictState(
            syncState = syncState,
            provider = provider,
            onShowConflictDialog = onShowConflictDialog,
            onShowReviewDialog = onShowReviewDialog,
        )
    }
}

private fun consumeProviderSyncConflictState(
    syncState: UnifiedSyncState,
    provider: SyncBackendType,
    onShowConflictDialog: (SyncConflictSet) -> Unit,
    onShowReviewDialog: (SyncReviewSession) -> Unit,
) {
    when (syncState) {
        is UnifiedSyncState.ConflictDetected -> {
            if (syncState.provider == provider) {
                onShowConflictDialog(syncState.conflicts)
            }
        }
        is UnifiedSyncState.ReviewRequired -> {
            if (syncState.provider == provider) {
                onShowReviewDialog(syncState.review)
            }
        }
        UnifiedSyncState.Idle,
        is UnifiedSyncState.Error,
        is UnifiedSyncState.NotConfigured,
        is UnifiedSyncState.Running,
        is UnifiedSyncState.Success,
        -> Unit
    }
}
