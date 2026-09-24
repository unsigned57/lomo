package com.lomo.ui.component.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * A render-list entry that pairs a source item with its exit state.
 *
 * [LomoListExitPhase.Exiting] means the item is mid two-phase exit (fade then collapse).
 * [LomoListExitPhase.Hidden] means the visual animation has settled but the source still
 * contains the item, so the caller must keep the row zero-height/transparent until source
 * absence is observed.
 */
data class LomoListExitRenderEntry<T>(
    val item: T,
    val snapshotMemo: T,
    val exitPhase: LomoListExitPhase?,
) {
    val isExiting: Boolean
        get() = exitPhase != null
}

enum class LomoListExitPhase {
    Exiting,
    Hidden,
}

/**
 * Builds the render list by merging [allItems] with active exits from the registry.
 */
fun <T> resolveExitRenderList(
    allItems: List<T>,
    itemKey: (T) -> String,
    activeExits: Map<String, ExitAnimationRegistry.ExitEntry<T>>,
): List<LomoListExitRenderEntry<T>> {
    return resolveExitRenderList(allItems, itemKey, activeExits, { it })
}

/**
 * Builds the render list by merging [allItems] with active exits from the registry.
 */
fun <T, R> resolveExitRenderList(
    allItems: List<T>,
    itemKey: (T) -> String,
    activeExits: Map<String, ExitAnimationRegistry.ExitEntry<R>>,
    mapExitToItem: (R) -> T,
): List<LomoListExitRenderEntry<T>> {
    val renderList = allItems.map { item ->
        val key = itemKey(item)
        val activeExit = activeExits[key]
        val snapshotMemo = activeExit?.let { entry -> mapExitToItem(entry.item) } ?: item
        LomoListExitRenderEntry(item = item, snapshotMemo = snapshotMemo, exitPhase = activeExit?.exitPhase)
    }.toMutableList()

    val allItemsKeys = allItems.map(itemKey).toSet()
    val retainedExits = activeExits.filterKeys { it !in allItemsKeys }

    if (retainedExits.isEmpty()) {
        return renderList
    }

    val pending = retainedExits.values.toMutableList()
    var progress = true
    while (pending.isNotEmpty() && progress) {
        progress = false
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val anchor = entry.anchoredAfterKey
            val mappedItem = mapExitToItem(entry.item)
            if (anchor == null) {
                renderList.add(
                    0,
                    LomoListExitRenderEntry(
                        item = mappedItem,
                        snapshotMemo = mappedItem,
                        exitPhase = entry.exitPhase,
                    )
                )
                iterator.remove()
                progress = true
            } else {
                val anchorIndex = renderList.indexOfFirst { itemKey(it.item) == anchor }
                if (anchorIndex >= 0) {
                    renderList.add(
                        anchorIndex + 1,
                        LomoListExitRenderEntry(
                            item = mappedItem,
                            snapshotMemo = mappedItem,
                            exitPhase = entry.exitPhase,
                        )
                    )
                    iterator.remove()
                    progress = true
                }
            }
        }
    }

    for (entry in pending) {
        val mappedItem = mapExitToItem(entry.item)
        renderList.add(
            LomoListExitRenderEntry(
                item = mappedItem,
                snapshotMemo = mappedItem,
                exitPhase = entry.exitPhase,
            )
        )
    }

    return renderList
}

/**
 * Maintains source keys across paging append/prepend without rebuilding the whole set.
 *
 * Refresh and middle edits fall back to a full scan. Paging's common append (stable prefix) and
 * prepend (stable suffix) only map the new page.
 */
fun <T> incrementalSourceKeys(
    previousKeys: List<String>?,
    nextItems: List<T>,
    itemKey: (T) -> String,
): List<String> {
    if (nextItems.isEmpty()) {
        return emptyList()
    }
    val previous = previousKeys
    if (previous.isNullOrEmpty()) {
        return nextItems.map(itemKey)
    }
    val nextSize = nextItems.size
    val previousSize = previous.size
    val firstMatches = itemKey(nextItems.first()) == previous.first()
    if (
        nextSize == previousSize &&
        firstMatches &&
        itemKey(nextItems.last()) == previous.last()
    ) {
        val unchanged =
            nextItems.indices.all { index -> itemKey(nextItems[index]) == previous[index] }
        if (unchanged) {
            return previous
        }
    }
    if (
        nextSize > previousSize &&
        firstMatches &&
        itemKey(nextItems[previousSize - 1]) == previous.last()
    ) {
        return previous + nextItems.subList(previousSize, nextSize).map(itemKey)
    }
    val prependCount = nextSize - previousSize
    if (
        nextSize > previousSize &&
        itemKey(nextItems.last()) == previous.last() &&
        itemKey(nextItems[prependCount]) == previous.first()
    ) {
        return nextItems.subList(0, prependCount).map(itemKey) + previous
    }
    return nextItems.map(itemKey)
}

internal class IncrementalSourceKeyTracker<T>(
    private val itemKey: (T) -> String,
) {
    private var previousKeys: List<String>? = null

    fun keysFor(nextItems: List<T>): Set<String> {
        val next = incrementalSourceKeys(previousKeys, nextItems, itemKey)
        previousKeys = next
        return next.toSet()
    }
}

/**
 * Composable state holder that drives list-level exit retention.
 */
class LomoListExitState<T>(
    val renderList: ImmutableList<LomoListExitRenderEntry<T>>,
    val overlayIdle: Boolean,
    val onExitSettled: (String) -> Unit,
)

@Composable
fun <T> rememberLomoListExitState(
    registry: ExitAnimationRegistry<T>,
    allItems: List<T>,
    itemKey: (T) -> String,
): LomoListExitState<T> {
    return rememberLomoListExitState(registry, allItems, itemKey, { it })
}

@Composable
fun <T, R> rememberLomoListExitState(
    registry: ExitAnimationRegistry<R>,
    allItems: List<T>,
    itemKey: (T) -> String,
    mapExitToItem: (R) -> T,
): LomoListExitState<T> {
    val activeExits by registry.entries.collectAsStateWithLifecycle()
    val overlayIdle = activeExits.isEmpty()
    val keyTracker = remember(itemKey) { IncrementalSourceKeyTracker(itemKey) }
    val sourceKeys = remember(allItems) { keyTracker.keysFor(allItems) }

    LaunchedEffect(sourceKeys, activeExits) {
        registry.updateSourceKeys(sourceKeys)
    }

    val renderList =
        remember(allItems, activeExits, overlayIdle) {
            if (overlayIdle) {
                persistentListOf()
            } else {
                resolveExitRenderList(
                    allItems = allItems,
                    itemKey = itemKey,
                    activeExits = activeExits,
                    mapExitToItem = mapExitToItem,
                ).toImmutableList()
            }
        }

    return remember(renderList, overlayIdle) {
        LomoListExitState(
            renderList = renderList,
            overlayIdle = overlayIdle,
            onExitSettled = { id ->
                registry.markExitAnimationSettled(id)
            },
        )
    }
}



private const val DUPLICATE_RENDER_KEY_MARKER = "\u0000dup-"

fun uniqueMemoListRenderKeys(baseKeys: List<String>): List<String> =
    HashSet<String>(baseKeys.size).let { seen ->
        baseKeys.mapIndexed { index, base ->
            if (seen.add(base)) {
                base
            } else {
                var candidate = "$base$DUPLICATE_RENDER_KEY_MARKER$index"
                while (!seen.add(candidate)) {
                    candidate += DUPLICATE_RENDER_KEY_MARKER
                }
                candidate
            }
        }
    }

/**
 * Unique LazyColumn keys for the materialized window only. Unloaded ranks use a formula keyed by
 * absolute index, so placeholder-backed `itemCount` never allocates an O(library) key list.
 */
data class ExitRenderKeyWindow(
    val startIndex: Int,
    val keys: ImmutableList<String>,
) {
    fun keyAt(index: Int): String =
        keys.getOrNull(index - startIndex) ?: "placeholder-$index"
}

fun computeItemKeyWindow(
    snapshotStartIndex: Int,
    keys: List<String>,
): ExitRenderKeyWindow {
    require(snapshotStartIndex >= 0) { "snapshotStartIndex must be non-negative" }
    return ExitRenderKeyWindow(
        startIndex = snapshotStartIndex,
        keys = uniqueMemoListRenderKeys(keys).toImmutableList(),
    )
}

fun <T> computeExitRenderKeyWindow(
    snapshotStartIndex: Int,
    renderList: ImmutableList<LomoListExitRenderEntry<T>>,
    itemKey: (T) -> String,
): ExitRenderKeyWindow =
    computeItemKeyWindow(
        snapshotStartIndex = snapshotStartIndex,
        keys = List(renderList.size) { offset -> itemKey(renderList[offset].snapshotMemo) },
    )

@Composable
fun <T> rememberUniqueExitRenderListKeys(
    snapshotStartIndex: Int,
    renderList: ImmutableList<LomoListExitRenderEntry<T>>,
    itemKey: (T) -> String,
    itemSnapshotList: Any?,
): ExitRenderKeyWindow =
    remember(snapshotStartIndex, renderList, itemSnapshotList) {
        computeExitRenderKeyWindow(
            snapshotStartIndex = snapshotStartIndex,
            renderList = renderList,
            itemKey = itemKey,
        )
    }
