package com.lomo.ui.component.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.cd_collapse
import com.lomo.ui.generated.resources.cd_expand
import com.lomo.ui.generated.resources.sidebar_tags
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback
import sh.calvin.reorderable.ReorderableItem

internal fun LazyListScope.sidebarTags(input: SidebarTagsInput) {
    val tags = input.tags
    val visibleRows = input.visibleRows
    val tagTree = input.tagTree
    val expandedNodes = input.expandedNodes
    val selectedTagPath = input.selectedTagPath
    val onTagClick = input.onTagClick
    val anchorTagForPath = input.anchorTagForPath
    val reorderableLazyListState = input.reorderableLazyListState
    val onReorderComplete = input.onReorderComplete
    if (tags.isEmpty()) return

    item(key = "sidebar_tags_header") {
        Text(
            text = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_tags),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = AppSpacing.Medium, top = AppSpacing.Medium, bottom = AppSpacing.Small),
        )
    }

    items(visibleRows, key = { row -> row.node.fullPath }) { row ->
        val rowModifier =
            Modifier.animateItem(
                fadeInSpec = LocalLomoMotion.current.layout.fastEffectsSpec(),
                placementSpec = LocalLomoMotion.current.layout.defaultSpatialSpec(),
                fadeOutSpec = LocalLomoMotion.current.layout.fastEffectsSpec(),
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
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isDragging) SidebarDrawerTokens.TagDragScale else 1f,
        animationSpec = LocalLomoMotion.current.component.fastSpatialSpec(),
        label = "TagDragScale",
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isDragging) SidebarDrawerTokens.TagDragAlpha else 1f,
        animationSpec = LocalLomoMotion.current.component.fastEffectsSpec(),
        label = "TagDragAlpha",
    )
    val haptic = LocalAppHapticFeedback.current
    Box(
        modifier =
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
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
    val isExpanded = expandedNodes[node.fullPath] == true
    val hasChildren = node.children.isNotEmpty()

    SidebarTagRow(
        node = node,
        level = row.level,
        isSelected = selectedTagPath == node.fullPath,
        hasChildren = hasChildren,
        isExpanded = isExpanded,
        onTagClick = onTagClick,
        onToggleExpand = { path -> expandedNodes[path] = !(expandedNodes[path] == true) },
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
    val colorScheme = MaterialTheme.colorScheme
    val containerColor =
        if (isSelected) {
            colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }
    val contentColor =
        if (isSelected) {
            colorScheme.onSecondaryContainer
        } else {
            colorScheme.onSurface
        }
    val leadingAndTrailingTint =
        if (isSelected) {
            colorScheme.onSecondaryContainer
        } else {
            colorScheme.onSurfaceVariant
        }

    val outlineVariant = colorScheme.outlineVariant

    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .drawTagGuidelines(level, outlineVariant),
    ) {
        Surface(
            onClick = rememberTagClick(node.fullPath, onTagClick),
            shape = SidebarDrawerTokens.TagRowShape,
            color = containerColor,
            contentColor = contentColor,
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = SidebarDrawerTokens.TagRowStartPadding * level)
                .benchmarkAnchor(anchorTag),
        ) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SidebarDrawerTokens.TagRowStartPadding,
                        end = SidebarDrawerTokens.TagRowEndPadding,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LomoIcon(
                    icon = LomoIcons.Tag,
                    contentDescription = null,
                    tint = leadingAndTrailingTint,
                    modifier = Modifier.size(SidebarDrawerTokens.TagLeadingIconSize),
                )
                Spacer(modifier = Modifier.width(SidebarDrawerTokens.TagLabelSpacing))
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalContentColor provides leadingAndTrailingTint,
                ) {
                    TagTreeTrailingContent(
                        node = node,
                        hasChildren = hasChildren,
                        isExpanded = isExpanded,
                        onToggleExpand = onToggleExpand,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagTreeTrailingContent(
    node: TagNode,
    hasChildren: Boolean,
    isExpanded: Boolean,
    onToggleExpand: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        node.count?.let { count ->
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current,
            )
        }
        if (hasChildren) {
            Spacer(modifier = Modifier.width(AppSpacing.ExtraSmall))
            TagTreeExpandButton(
                isExpanded = isExpanded,
                onClick = { onToggleExpand(node.fullPath) },
            )
        }
    }
}

@Composable
private fun TagTreeExpandButton(isExpanded: Boolean, onClick: () -> Unit) {
    val color = LocalContentColor.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isExpanded) {
            if (rtl) -90f else 90f
        } else {
            0f
        },
        animationSpec = LocalLomoMotion.current.component.fastSpatialSpec(),
        label = "TagExpandIndicator",
    )
    LomoIconButton(
        onClick = rememberMediumHapticClick(onClick),
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
    ) {
        LomoIcon(
            LomoIcons.KeyboardArrowRight,
            contentDescription =
            org.jetbrains.compose.resources.stringResource(
                if (isExpanded) {
                    Res.string.cd_collapse
                } else {
                    Res.string.cd_expand
                },
            ),
            modifier = Modifier.size(SidebarDrawerTokens.TagExpandIconSize).graphicsLayer { rotationZ = rotation },
            tint = color,
        )
    }
}

@Composable
private fun rememberTagClick(fullPath: String, onTagClick: (String) -> Unit): () -> Unit {
    val haptic = LocalAppHapticFeedback.current
    return remember(fullPath, onTagClick, haptic) {
        {
            haptic.medium()
            onTagClick(fullPath)
        }
    }
}

private fun Modifier.drawTagGuidelines(level: Int, color: Color): Modifier = drawBehind {
    // If level > 0, draw vertical dashed guidelines for hierarchy
    if (level > 0) {
        val stepPx = SidebarDrawerTokens.TagRowStartPadding.toPx()
        val startOffsetPx = stepPx / 2f
        val dashPathEffect =
            PathEffect.dashPathEffect(
                floatArrayOf(4f, 4f), // 4px line, 4px gap
                0f,
            )

        for (i in 0 until level) {
            val x = startOffsetPx + i * stepPx
            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashPathEffect,
            )
        }
    }
}
