package com.lomo.app.feature.conflict

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.usecase.RemoteSyncConflictDialogUseCase
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet

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

/**
 * Poll Rust open conflicts when Direct workspace root is available.
 * Opens the original dialog via [onShowRemoteSession] (not Sync Center list-detail).
 */
@Composable
internal fun RemoteSyncConflictPollHost(
    workspaceRoot: String?,
    onShowRemoteSession: (RemoteSyncConflictDialogUseCase.OpenSession) -> Unit,
    loadSession: (String) -> RemoteSyncConflictDialogUseCase.OpenSession?,
) {
    LaunchedEffect(workspaceRoot) {
        val root = workspaceRoot ?: return@LaunchedEffect
        val session = loadSession(root) ?: return@LaunchedEffect
        onShowRemoteSession(session)
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
            // Remote engines no longer own ConflictDetected; ignore dual-stack leftovers.
            // Presentation-only payload may still open dialog without Rust session (resolve fail-closed).
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
