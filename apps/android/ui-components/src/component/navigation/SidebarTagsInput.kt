package com.lomo.ui.component.navigation

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import sh.calvin.reorderable.ReorderableLazyListState

/** One sidebar tag-tree section: the rows to draw plus their interaction handlers. */
internal data class SidebarTagsInput(
    val tags: List<SidebarTag>,
    val visibleRows: List<VisibleTagRow>,
    val tagTree: SnapshotStateList<TagNode>,
    val expandedNodes: SnapshotStateMap<String, Boolean>,
    val selectedTagPath: String?,
    val onTagClick: (String) -> Unit,
    val anchorTagForPath: (String) -> String?,
    val reorderableLazyListState: ReorderableLazyListState,
    val onReorderComplete: (List<String>) -> Unit,
)
