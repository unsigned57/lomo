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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.lomoListItemMotion
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

internal fun LazyListScope.sidebarTags(
    tags: List<SidebarTag>,
    visibleRows: List<VisibleTagRow>,
    tagTree: SnapshotStateList<TagNode>,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    selectedTagPath: String?,
    onTagClick: (String) -> Unit,
    anchorTagForPath: (String) -> String?,
    reorderableLazyListState: ReorderableLazyListState,
    onReorderComplete: (List<String>) -> Unit,
) {
    if (tags.isEmpty()) return

    item(key = "sidebar_tags_header") {
        Text(
            text = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_tags),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = AppSpacing.Small),
        )
    }

    items(visibleRows, key = { row -> row.node.fullPath }) { row ->
        val rowModifier =
            Modifier.lomoListItemMotion(this)
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
                        scaleX = SidebarDrawerTokens.TagDragScale
                        scaleY = SidebarDrawerTokens.TagDragScale
                        alpha = SidebarDrawerTokens.TagDragAlpha
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
            SidebarDrawerTokens.selectedContainerColor(MaterialTheme.colorScheme)
        } else {
            Color.Transparent
        }
    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    val border = null

    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // If level > 0, draw vertical dashed guidelines for hierarchy
                if (level > 0) {
                    val stepPx = with(density) { SidebarDrawerTokens.TagRowStartPadding.toPx() }
                    val startOffsetPx = stepPx / 2f
                    val dashPathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4f, 4f), // 4px line, 4px gap
                        0f
                    )
                    
                    for (i in 0 until level) {
                        val x = startOffsetPx + i * stepPx
                        drawLine(
                            color = outlineVariant,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dashPathEffect
                        )
                    }
                }
            }
    ) {
        Surface(
            onClick = rememberTagClick(node.fullPath, onTagClick),
            shape = SidebarDrawerTokens.TagRowShape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SidebarDrawerTokens.TagRowStartPadding * level)
                .height(SidebarDrawerTokens.RowHeight)
                .benchmarkAnchor(anchorTag),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = SidebarDrawerTokens.TagRowStartPadding,
                            end = SidebarDrawerTokens.TagRowEndPadding,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagTreeLeadingIcon(contentColor)
                Spacer(modifier = Modifier.width(SidebarDrawerTokens.TagLabelSpacing))
                TagTreeLabel(
                    node = node,
                    color = contentColor,
                    isSelected = isSelected,
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
}

@Composable
private fun TagTreeLabel(
    node: TagNode,
    color: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        node.name,
        style = if (isSelected) {
            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        } else {
            MaterialTheme.typography.bodyMedium
        },
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
        modifier = Modifier.size(SidebarDrawerTokens.TagLeadingIconSize),
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
        modifier = Modifier.size(SidebarDrawerTokens.TagExpandButtonSize),
    ) {
        Icon(
            if (isExpanded) {
                Icons.Rounded.KeyboardArrowDown
            } else {
                Icons.AutoMirrored.Rounded.KeyboardArrowRight
            },
            contentDescription =
                org.jetbrains.compose.resources.stringResource(
                    if (isExpanded) {
                        Res.string.cd_collapse
                    } else {
                        Res.string.cd_expand
                    },
                ),
            modifier = Modifier.size(SidebarDrawerTokens.TagExpandIconSize),
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
