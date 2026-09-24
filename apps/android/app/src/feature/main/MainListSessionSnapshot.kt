package com.lomo.app.feature.main

import androidx.compose.runtime.saveable.Saver
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import java.time.LocalDate

/**
 * One durable main-list session: query, structural filter and viewport anchor bound to the
 * workspace identity they were captured under.
 *
 * A screen session is only coherent when all four fields travel together — a saved anchor without
 * its query or workspace would resurrect a stale page, and a saved query without its workspace
 * would leak into a different mount.
 */
internal data class MainListSessionSnapshot(
    val workspacePath: String?,
    val searchQuery: String,
    val filter: MemoListFilter,
    val anchorIndex: Int,
    val anchorOffset: Int,
) {
    companion object {
        val Empty =
            MainListSessionSnapshot(
                workspacePath = null,
                searchQuery = "",
                filter = MemoListFilter(),
                anchorIndex = 0,
                anchorOffset = 0,
            )
    }
}

internal sealed interface MainListSessionRestoreAction {
    /** The mounted workspace owns the snapshot: restore query and filter, then the anchor. */
    data class Apply(
        val snapshot: MainListSessionSnapshot,
    ) : MainListSessionRestoreAction

    /** The mounted workspace does not own the snapshot: bind it and start at the top. */
    data object Reset : MainListSessionRestoreAction

    /** The mount has not published a workspace location yet: keep waiting. */
    data object Wait : MainListSessionRestoreAction
}

internal fun resolveMainListSessionRestore(
    snapshot: MainListSessionSnapshot,
    workspacePath: String?,
): MainListSessionRestoreAction =
    when {
        workspacePath == null -> MainListSessionRestoreAction.Wait
        snapshot.workspacePath == workspacePath -> MainListSessionRestoreAction.Apply(snapshot)
        else -> MainListSessionRestoreAction.Reset
    }

private const val SESSION_FIELD_COUNT = 11

internal val mainListSessionSnapshotSaver =
    Saver<MainListSessionSnapshot, List<Any?>>(
        save = { snapshot ->
            listOf(
                snapshot.workspacePath,
                snapshot.searchQuery,
                snapshot.anchorIndex,
                snapshot.anchorOffset,
                snapshot.filter.sortOption.name,
                snapshot.filter.sortAscending,
                snapshot.filter.startDate?.toString(),
                snapshot.filter.endDate?.toString(),
                snapshot.filter.hasTodo,
                snapshot.filter.hasAttachment,
                snapshot.filter.hasUrl,
            )
        },
        restore = { restored ->
            require(restored.size == SESSION_FIELD_COUNT) {
                "Main list session snapshot expects $SESSION_FIELD_COUNT fields, got ${restored.size}"
            }
            MainListSessionSnapshot(
                workspacePath = restored[0] as String?,
                searchQuery = restored[1] as String,
                anchorIndex = restored[2] as Int,
                anchorOffset = restored[3] as Int,
                filter =
                    MemoListFilter(
                        sortOption = MemoSortOption.valueOf(restored[4] as String),
                        sortAscending = restored[5] as Boolean,
                        startDate = (restored[6] as String?)?.let(LocalDate::parse),
                        endDate = (restored[7] as String?)?.let(LocalDate::parse),
                        hasTodo = restored[8] as Boolean?,
                        hasAttachment = restored[9] as Boolean?,
                        hasUrl = restored[10] as Boolean?,
                    ),
            )
        },
    )
