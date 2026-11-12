package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.util.findComponentActivity
import kotlinx.coroutines.flow.first

/**
 * Fires `reportFullyDrawn` once the first memo page settles — TTFD shows up in
 * `StartupTimingMetric`/`am start -W` as `Fully drawn`. When no workspace is configured the
 * refresh state never reaches `NotLoading`, so nothing is reported instead of a fake number.
 */
@Composable
internal fun MainScreenFullyDrawnEffect(pagedUiMemos: LazyPagingItems<MemoUiModel>) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        snapshotFlow { pagedUiMemos.loadState.refresh is LoadState.NotLoading }
            .first { loaded -> loaded }
        context.findComponentActivity()?.reportFullyDrawn()
    }
}
