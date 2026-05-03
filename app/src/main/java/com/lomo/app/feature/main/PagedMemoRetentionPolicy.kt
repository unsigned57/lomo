package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.lomo.app.feature.common.DELETE_COLLAPSE_SETTLE_DURATION_MILLIS
import com.lomo.app.feature.common.mergeVisibleItemsWithRetainedItems
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun <T> mergePagedVisibleItemsWithRetainedRows(
    sourceItems: List<T>,
    previousVisibleItems: List<T>,
    retainedIds: Set<String>,
    itemId: (T) -> String,
): List<T> =
    mergeVisibleItemsWithRetainedItems(
        sourceItems = sourceItems,
        previousVisibleItems = previousVisibleItems,
        retainedIds = retainedIds,
        itemId = itemId,
    )

internal fun <T> resolvePagedRetentionCleanupIds(
    sourceItems: List<T>,
    retainedIds: Set<String>,
    itemId: (T) -> String,
): Set<String> {
    val sourceIds = sourceItems.asSequence().map(itemId).toSet()
    return retainedIds.filterTo(mutableSetOf()) { retainedId -> retainedId !in sourceIds }
}

@Composable
internal fun <T> rememberRetainedPagedItems(
    sourceItems: ImmutableList<T>,
    retainedIds: ImmutableSet<String>,
    itemId: (T) -> String,
    onRetentionSettled: (String) -> Unit,
): ImmutableList<T> {
    var visibleItems by remember { mutableStateOf(sourceItems) }
    val cleanupJobs = remember { mutableMapOf<String, Job>() }
    val latestOnRetentionSettled by rememberUpdatedState(onRetentionSettled)

    DisposableEffect(Unit) {
        onDispose {
            cleanupJobs.values.forEach(Job::cancel)
            cleanupJobs.clear()
        }
    }

    LaunchedEffect(sourceItems, retainedIds) {
        visibleItems =
            mergePagedVisibleItemsWithRetainedRows(
                sourceItems = sourceItems,
                previousVisibleItems = visibleItems,
                retainedIds = retainedIds,
                itemId = itemId,
            ).toImmutableList()

        val cleanupIds =
            resolvePagedRetentionCleanupIds(
                sourceItems = sourceItems,
                retainedIds = retainedIds,
                itemId = itemId,
            )

        cleanupJobs.keys
            .filter { id -> id !in cleanupIds }
            .toList()
            .forEach { id ->
                cleanupJobs.remove(id)?.cancel()
            }

        cleanupIds.forEach { id ->
            if (cleanupJobs.containsKey(id)) {
                return@forEach
            }
            cleanupJobs[id] =
                launch {
                    delay(DELETE_COLLAPSE_SETTLE_DURATION_MILLIS)
                    cleanupJobs.remove(id)
                    latestOnRetentionSettled(id)
                }
        }
    }

    return visibleItems
}
