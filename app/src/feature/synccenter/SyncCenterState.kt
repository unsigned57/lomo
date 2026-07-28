package com.lomo.app.feature.synccenter

import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionProgress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * Stage-5 dark Sync Center UI state (P5-10).
 *
 * Pure presentation state for host tests + Compose shell. Not production-navigated.
 */

enum class SyncCenterPane {
    Overview,
    Conflicts,
    ConflictDetail,
    Recovery,
}

data class SyncCenterLayoutMode(
    val isListDetail: Boolean,
)

sealed interface SyncCenterLoadState {
    data object Idle : SyncCenterLoadState

    data object Loading : SyncCenterLoadState

    data class Ready(
        val config: RemoteSyncConfigSummary,
        val session: RemoteSyncSessionProgress,
        val conflictPage: RemoteSyncConflictPage,
        val items: ImmutableList<RemoteSyncConflictPath>,
        val selectedPath: String?,
        val perPathResolutionKind: ImmutableMap<String, String>,
        val mergedDrafts: ImmutableMap<String, String>,
        /**
         * Detail facts loaded via domain remote-sync center detail ports on select.
         * Compose must prefer these over digest-only helpers.
         */
        val markdownDetailByPath: ImmutableMap<String, RemoteSyncMarkdownConflictFacts> =
            persistentMapOf(),
        val binaryDetailByPath: ImmutableMap<String, RemoteSyncBinaryConflictFacts> =
            persistentMapOf(),
        val isLoadingDetail: Boolean = false,
        val isResolving: Boolean,
        val lastError: String?,
        val appliedPaths: ImmutableList<String>,
    ) : SyncCenterLoadState

    data class Failed(
        val message: String,
    ) : SyncCenterLoadState
}

data class SyncCenterUiState(
    val workspaceRoot: String,
    val pane: SyncCenterPane,
    val layout: SyncCenterLayoutMode,
    val load: SyncCenterLoadState,
)

sealed interface SyncCenterIntent {
    data class Open(
        val workspaceRoot: String,
        val isListDetail: Boolean,
    ) : SyncCenterIntent

    data object Refresh : SyncCenterIntent

    data object LoadMoreConflicts : SyncCenterIntent

    data class SelectConflict(
        val path: String,
    ) : SyncCenterIntent

    data object ClearSelection : SyncCenterIntent

    data class SetResolutionKind(
        val path: String,
        val kind: String,
    ) : SyncCenterIntent

    data class SetMergedDraft(
        val path: String,
        val draft: String,
    ) : SyncCenterIntent

    data object ApplyResolutions : SyncCenterIntent

    data object NavigateOverview : SyncCenterIntent

    data object NavigateConflicts : SyncCenterIntent

    data object NavigateRecovery : SyncCenterIntent

    data class SetListDetail(
        val isListDetail: Boolean,
    ) : SyncCenterIntent

    data object CancelSession : SyncCenterIntent
}

sealed interface SyncCenterEffect {
    data class LoadInitial(
        val workspaceRoot: String,
    ) : SyncCenterEffect

    data class LoadMore(
        val workspaceRoot: String,
        val cursor: Int,
        val limit: Int,
    ) : SyncCenterEffect

    data class Resolve(
        val workspaceRoot: String,
        val expectedRevision: Long,
        val resolutions: List<RemoteSyncConflictResolution>,
    ) : SyncCenterEffect

    /**
     * Load markdown or binary detail facts for the selected path via domain detail ports.
     * Emitted on [SyncCenterIntent.SelectConflict] when the path is markdown or binary.
     */
    data class LoadConflictDetail(
        val workspaceRoot: String,
        val path: RemoteSyncConflictPath,
        val mergedDraft: String?,
    ) : SyncCenterEffect

    /** Presentation-only cancel request; production runner wires later (P5-13). */
    data object RequestCancel : SyncCenterEffect
}

data class SyncCenterReduceResult(
    val state: SyncCenterUiState,
    val effects: List<SyncCenterEffect> = emptyList(),
)

internal const val SYNC_CENTER_CONFLICT_PAGE_LIMIT: Int = 100

fun initialSyncCenterState(
    workspaceRoot: String = "",
    isListDetail: Boolean = false,
): SyncCenterUiState =
    SyncCenterUiState(
        workspaceRoot = workspaceRoot,
        pane = SyncCenterPane.Overview,
        layout = SyncCenterLayoutMode(isListDetail = isListDetail),
        load = SyncCenterLoadState.Idle,
    )
