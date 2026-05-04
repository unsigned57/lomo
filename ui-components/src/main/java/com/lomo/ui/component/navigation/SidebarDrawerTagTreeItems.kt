package com.lomo.ui.component.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LazyListMotionState
import com.lomo.ui.component.common.lazyListMotionItem
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

private val SidebarTagRowShape = RoundedCornerShape(28.dp)

internal fun LazyListScope.sidebarTags(
    tags: List<SidebarTag>,
    visibleRows: List<VisibleTagRow>,
    tagTree: SnapshotStateList<TagNode>,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    selectedTagPath: String?,
    onTagClick: (String) -> Unit,
    anchorTagForPath: (String) -> String?,
    reorderableLazyListState: ReorderableLazyListState,
    motionState: LazyListMotionState,
    onReorderComplete: (List<String>) -> Unit,
) {
    if (tags.isEmpty()) return

    item(key = "sidebar_tags_header") {
        Text(
            text = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.sidebar_tags),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = AppSpacing.Small),
        )
    }

    items(visibleRows, key = { row -> row.node.fullPath }) { row ->
        val rowModifier =
            Modifier.lazyListMotionItem(
                lazyItemScope = this,
                itemKey = row.node.fullPath,
                motionState = motionState,
            )
        if (row.level == 0) {
            ReorderableItem(
                state = reorderableLazyListState,
                key = row.node.fullPath,
            ) { isDragging ->
                DraggableRootTagRow(
                    row = row,
                    isDragging = isDragging,
                    modifier = rowModifier,
                    tagTree = tagTree,
                    selectedTagPath = selectedTagPath,
                    expandedNodes = expandedNodes,
                    onTagClick = onTagClick,
                    onReorderComplete = onReorderComplete,
                    anchorTagForPath = anchorTagForPath,
                )
            }
        } else {
            TagTreeItem(
                row = row,
                expandedNodes = expandedNodes,
                selectedTagPath = selectedTagPath,
                onTagClick = onTagClick,
                anchorTagForPath = anchorTagForPath,
                modifier = rowModifier,
            )
        }
    }
}

@Composable
private fun sh.calvin.reorderable.ReorderableCollectionItemScope.DraggableRootTagRow(
    row: VisibleTagRow,
    isDragging: Boolean,
    tagTree: SnapshotStateList<TagNode>,
    selectedTagPath: String?,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    onTagClick: (String) -> Unit,
    onReorderComplete: (List<String>) -> Unit,
    anchorTagForPath: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    if (isDragging) {
                        scaleX = TAG_DRAG_SCALE
                        scaleY = TAG_DRAG_SCALE
                        alpha = TAG_DRAG_ALPHA
                    }
                }
                .longPressDraggableHandle(
                    onDragStarted = { haptic.heavy() },
                    onDragStopped = { onReorderComplete(tagTree.map { it.name }) },
                ),
    ) {
        TagTreeItem(
            row = row,
            expandedNodes = expandedNodes,
            selectedTagPath = selectedTagPath,
            onTagClick = onTagClick,
            anchorTagForPath = anchorTagForPath,
            modifier = Modifier,
        )
    }
}

@Composable
private fun TagTreeItem(
    row: VisibleTagRow,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    selectedTagPath: String?,
    onTagClick: (String) -> Unit,
    anchorTagForPath: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val node = row.node
    val isExpanded = expandedNodes[node.fullPath] ?: false
    val hasChildren = node.children.isNotEmpty()

    SidebarTagRow(
        node = node,
        level = row.level,
        isSelected = selectedTagPath == node.fullPath,
        hasChildren = hasChildren,
        isExpanded = isExpanded,
        onTagClick = onTagClick,
        onToggleExpand = { path -> expandedNodes[path] = !(expandedNodes[path] ?: false) },
        anchorTag = anchorTagForPath(node.fullPath),
        modifier = modifier,
    )
}

@Composable
private fun SidebarTagRow(
    node: TagNode,
    level: Int,
    isSelected: Boolean,
    hasChildren: Boolean,
    isExpanded: Boolean,
    onTagClick: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    anchorTag: String?,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        onClick = rememberTagClick(node.fullPath, onTagClick),
        shape = SidebarTagRowShape,
        color = containerColor,
        contentColor = contentColor,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = (level * AppSpacing.Medium.value).dp)
                .height(48.dp)
                .benchmarkAnchor(anchorTag),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = AppSpacing.Medium, end = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TagTreeLeadingIcon(contentColor)
            Spacer(modifier = Modifier.width(AppSpacing.MediumSmall))
            TagTreeLabel(
                node = node,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            TagTreeTrailingContent(
                node = node,
                hasChildren = hasChildren,
                isExpanded = isExpanded,
                color = contentColor,
                onToggleExpand = onToggleExpand,
            )
        }
    }
}

@Composable
private fun TagTreeLabel(
    node: TagNode,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        node.name,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun TagTreeLeadingIcon(color: Color) {
    Icon(
        Icons.Outlined.Tag,
        null,
        modifier = Modifier.size(20.dp),
        tint = color,
    )
}

@Composable
private fun TagTreeTrailingContent(
    node: TagNode,
    hasChildren: Boolean,
    isExpanded: Boolean,
    color: Color,
    onToggleExpand: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        node.count?.let { count ->
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        if (hasChildren) {
            Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
            TagTreeExpandButton(
                isExpanded = isExpanded,
                color = color,
                onClick = { onToggleExpand(node.fullPath) },
            )
        }
    }
}

@Composable
private fun TagTreeExpandButton(
    isExpanded: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = rememberLightHapticClick(onClick),
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            if (isExpanded) {
                Icons.Rounded.KeyboardArrowDown
            } else {
                Icons.AutoMirrored.Rounded.KeyboardArrowRight
            },
            contentDescription =
                androidx.compose.ui.res.stringResource(
                    if (isExpanded) {
                        com.lomo.ui.R.string.cd_collapse
                    } else {
                        com.lomo.ui.R.string.cd_expand
                    },
                ),
            modifier = Modifier.size(18.dp),
            tint = color,
        )
    }
}

@Composable
private fun rememberTagClick(
    fullPath: String,
    onTagClick: (String) -> Unit,
): () -> Unit {
    val haptic = LocalAppHapticFeedback.current
    return remember(fullPath, onTagClick, haptic) {
        {
            haptic.light()
            onTagClick(fullPath)
        }
    }
}
