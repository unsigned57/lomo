package com.lomo.ui.component.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
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
 * Composable state holder that drives list-level exit retention.
 */
class LomoListExitState<T>(
    val renderList: ImmutableList<LomoListExitRenderEntry<T>>,
    val onExitSettled: (String) -> Unit,
)

@Composable
fun <T> rememberLomoListExitState(
    registry: ExitAnimationRegistry<T>,
    allItems: ImmutableList<T>,
    itemKey: (T) -> String,
): LomoListExitState<T> {
    return rememberLomoListExitState(registry, allItems, itemKey, { it })
}

@Composable
fun <T, R> rememberLomoListExitState(
    registry: ExitAnimationRegistry<R>,
    allItems: ImmutableList<T>,
    itemKey: (T) -> String,
    mapExitToItem: (R) -> T,
): LomoListExitState<T> {
    val activeExits by registry.entries.collectAsStateWithLifecycle()

    val sourceKeys =
        remember(allItems) {
            allItems
                .asSequence()
                .map(itemKey)
                .toSet()
        }

    LaunchedEffect(sourceKeys, activeExits) {
        registry.updateSourceKeys(sourceKeys)
    }

    val renderList by remember(allItems, activeExits) {
        derivedStateOf {
            resolveExitRenderList(
                allItems = allItems,
                itemKey = itemKey,
                activeExits = activeExits,
                mapExitToItem = mapExitToItem,
            ).toImmutableList()
        }
    }

    return remember(renderList) {
        LomoListExitState(
            renderList = renderList,
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

fun <T> computeExitRenderListBaseKeys(
    totalItemCount: Int,
    snapshotStartIndex: Int,
    renderList: ImmutableList<LomoListExitRenderEntry<T>>,
    itemKey: (T) -> String,
    peekItem: (Int) -> T?
): List<String> = List(totalItemCount) { index ->
    val entry = renderList.getOrNull(index - snapshotStartIndex)
    entry?.snapshotMemo?.let { itemKey(it) }
        ?: peekItem(index)?.let { itemKey(it) }
        ?: "placeholder-$index"
}

@Composable
fun <T> rememberUniqueExitRenderListKeys(
    totalItemCount: Int,
    snapshotStartIndex: Int,
    renderList: ImmutableList<LomoListExitRenderEntry<T>>,
    itemKey: (T) -> String,
    peekItem: (Int) -> T?,
    itemSnapshotList: Any?,
): ImmutableList<String> = remember(totalItemCount, snapshotStartIndex, renderList, itemSnapshotList) {
    uniqueMemoListRenderKeys(
        computeExitRenderListBaseKeys(
            totalItemCount = totalItemCount,
            snapshotStartIndex = snapshotStartIndex,
            renderList = renderList,
            itemKey = itemKey,
            peekItem = peekItem,
        )
    ).toImmutableList()
}
