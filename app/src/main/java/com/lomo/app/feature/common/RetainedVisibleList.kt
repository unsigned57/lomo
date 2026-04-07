package com.lomo.app.feature.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal const val DELETE_COLLAPSE_SETTLE_DURATION_MILLIS = 220L

internal class RetainedVisibleListTracker<T>(
    private val scope: CoroutineScope,
    private val sourceItemsProvider: () -> List<T>,
    private val deletingIds: MutableStateFlow<Set<String>>,
    private val retainedIds: MutableStateFlow<Set<String>>,
    private val itemId: (T) -> String,
    private val settleDurationMs: Long = DELETE_COLLAPSE_SETTLE_DURATION_MILLIS,
) {
    private val cleanupJobs = mutableMapOf<String, Job>()
    val visibleItems = MutableStateFlow<List<T>>(emptyList())

    fun reconcile(
        sourceItems: List<T>,
        retainedIdsSnapshot: Set<String>,
    ) {
        visibleItems.value =
            mergeVisibleItemsWithRetainedItems(
                sourceItems = sourceItems,
                previousVisibleItems = visibleItems.value,
                retainedIds = retainedIdsSnapshot,
                itemId = itemId,
            )

        val sourceIds = sourceItems.asSequence().map(itemId).toSet()
        cleanupJobs.keys
            .filter { id -> id !in retainedIdsSnapshot || id in sourceIds }
            .toList()
            .forEach(::cancelCleanup)
        retainedIdsSnapshot
            .filter { id -> id !in sourceIds }
            .forEach(::scheduleCleanup)
        deletingIds.value =
            deletingIds.value.filterTo(mutableSetOf()) { id ->
                id in sourceIds || id in retainedIdsSnapshot
            }
    }

    private fun scheduleCleanup(id: String) {
        if (cleanupJobs.containsKey(id)) {
            return
        }
        cleanupJobs[id] =
            scope.launch {
                delay(settleDurationMs)
                retainedIds.value = retainedIds.value - id
                deletingIds.value = deletingIds.value - id
                cleanupJobs.remove(id)
                visibleItems.value =
                    mergeVisibleItemsWithRetainedItems(
                        sourceItems = sourceItemsProvider(),
                        previousVisibleItems = visibleItems.value,
                        retainedIds = retainedIds.value,
                        itemId = itemId,
                    )
            }
    }

    private fun cancelCleanup(id: String) {
        cleanupJobs.remove(id)?.cancel()
    }
}

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
