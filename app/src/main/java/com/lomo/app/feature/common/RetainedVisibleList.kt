package com.lomo.app.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val DELETE_COLLAPSE_SETTLE_DURATION_MILLIS = 220L
private const val RETAINED_ITEM_CLEANUP_DELAY_MILLIS = 650L

internal fun <T> mergeVisibleItemsWithRetainedItems(
    sourceItems: List<T>,
    previousVisibleItems: List<T>,
    retainedIds: Set<String>,
    itemId: (T) -> String,
): List<T> {
    if (previousVisibleItems.isEmpty() || retainedIds.isEmpty()) {
        return sourceItems
    }

    val sourceIds = sourceItems.asSequence().map(itemId).toSet()
    val mergedItems = sourceItems.toMutableList()

    previousVisibleItems.forEachIndexed { previousIndex, previousItem ->
        val id = itemId(previousItem)
        if (id !in retainedIds || id in sourceIds) {
            return@forEachIndexed
        }
        val insertionIndex =
            resolveRetainedInsertionIndex(
                previousVisibleItems = previousVisibleItems,
                previousIndex = previousIndex,
                mergedItems = mergedItems,
                itemId = itemId,
            )
        mergedItems.add(insertionIndex, previousItem)
    }

    return mergedItems
}

internal fun <T> resolveRetentionCleanupIds(
    sourceItems: List<T>,
    retainedIds: Set<String>,
    itemId: (T) -> String,
): Set<String> {
    val sourceIds = sourceItems.asSequence().map(itemId).toSet()
    return retainedIds.filterTo(mutableSetOf()) { retainedId -> retainedId !in sourceIds }
}

/**
 * Synchronously computes the visible-item list by merging items that have disappeared from
 * [sourceItems] but are still held in [retainedIds] for exit animation. The merge happens
 * inside a [remember] block keyed on [sourceItems] and [retainedIds], so the retained item
 * never leaves the composition tree — [AnimatedVisibility] can play its exit transition
 * without interruption.
 *
 * Cleanup is scheduled asynchronously via [LaunchedEffect] after
 * [RETAINED_ITEM_CLEANUP_DELAY_MILLIS] (650 ms — long enough for the 600 ms composed
 * exit transition to finish plus a small buffer).
 */
@Composable
internal fun <T> rememberRetainedVisibleItems(
    sourceItems: ImmutableList<T>,
    retainedIds: ImmutableSet<String>,
    itemId: (T) -> String,
    onRetentionSettled: (String) -> Unit,
): ImmutableList<T> {
    val previousHolder = remember { PreviousItemsHolder<T>() }

    val visibleItems =
        remember(sourceItems, retainedIds) {
            val previousItems = previousHolder.items

            if (previousItems.isEmpty()) {
                previousHolder.items = sourceItems
                return@remember sourceItems
            }

            val currentIds = sourceItems.asSequence().map(itemId).toSet()

            val disappearingItems =
                previousItems.filter { prev ->
                    val id = itemId(prev)
                    id in retainedIds && id !in currentIds
                }

            if (disappearingItems.isEmpty()) {
                previousHolder.items = sourceItems
                return@remember sourceItems
            }

            val merged = sourceItems.toMutableList()
            for (disappearing in disappearingItems) {
                val prevIndex = previousItems.indexOfFirst { itemId(it) == itemId(disappearing) }
                val insertIndex =
                    resolveRetainedInsertionIndex(
                        previousVisibleItems = previousItems,
                        previousIndex = prevIndex,
                        mergedItems = merged,
                        itemId = itemId,
                    )
                merged.add(insertIndex, disappearing)
            }

            val result = merged.toImmutableList()
            previousHolder.items = result
            result
        }

    val latestOnRetentionSettled by rememberUpdatedState(onRetentionSettled)
    LaunchedEffect(retainedIds, sourceItems) {
        val currentIds = sourceItems.asSequence().map(itemId).toSet()
        val cleanupIds = retainedIds.filter { it !in currentIds }.toSet()

        cleanupIds.forEach { id ->
            launch {
                delay(RETAINED_ITEM_CLEANUP_DELAY_MILLIS)
                latestOnRetentionSettled(id)
            }
        }
    }

    return visibleItems
}

private fun <T> resolveRetainedInsertionIndex(
    previousVisibleItems: List<T>,
    previousIndex: Int,
    mergedItems: List<T>,
    itemId: (T) -> String,
): Int {
    for (precedingIndex in previousIndex - 1 downTo 0) {
        val precedingId = itemId(previousVisibleItems[precedingIndex])
        val mergedIndex = mergedItems.indexOfFirst { item -> itemId(item) == precedingId }
        if (mergedIndex >= 0) {
            return mergedIndex + 1
        }
    }

    for (followingIndex in previousIndex + 1 until previousVisibleItems.size) {
        val followingId = itemId(previousVisibleItems[followingIndex])
        val mergedIndex = mergedItems.indexOfFirst { item -> itemId(item) == followingId }
        if (mergedIndex >= 0) {
            return mergedIndex
        }
    }

    return mergedItems.size
}

private class PreviousItemsHolder<T> {
    var items: ImmutableList<T> = persistentListOf()
}
