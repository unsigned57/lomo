package com.lomo.ui.component.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.theme.AppSpacing

internal object SidebarDrawerTagTree

internal class TagReorderState {
    var draggedKey by mutableStateOf<String?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set
    val isDragging: Boolean get() = draggedKey != null

    fun startDrag(key: String) {
        draggedKey = key
        dragOffset = 0f
    }

    fun updateDrag(delta: Float) {
        dragOffset += delta
    }

    fun endDrag() {
        draggedKey = null
        dragOffset = 0f
    }

    fun checkAndSwap(tagTree: SnapshotStateList<TagNode>, listState: LazyListState) {
        val currentKey = draggedKey ?: return
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.find { it.key == currentKey } ?: return
        val draggedCenter = draggedItem.offset + draggedItem.size / 2 + dragOffset
        val draggedIndex = tagTree.indexOfFirst { it.fullPath == currentKey }
        if (draggedIndex < 0) return

        val swapTarget = findSwapTarget(tagTree, visibleItems, currentKey, draggedIndex, draggedCenter)
        if (swapTarget != null) {
            val targetItem = visibleItems.first { it.key == tagTree[swapTarget].fullPath }
            val sizeAdjustment =
                if (swapTarget > draggedIndex) {
                    (targetItem.size - draggedItem.size).toFloat()
                } else {
                    -(targetItem.size - draggedItem.size).toFloat()
                }
            tagTree.add(swapTarget, tagTree.removeAt(draggedIndex))
            dragOffset += sizeAdjustment
        }
    }

    private fun findSwapTarget(
        tagTree: SnapshotStateList<TagNode>,
        visibleItems: List<androidx.compose.foundation.lazy.LazyListItemInfo>,
        currentKey: String,
        draggedIndex: Int,
        draggedCenter: Float,
    ): Int? =
        visibleItems
            .asSequence()
            .mapNotNull { item -> (item.key as? String)?.let { key -> key to item } }
            .filter { (key, _) -> key != currentKey }
            .mapNotNull { (key, item) ->
                val idx = tagTree.indexOfFirst { it.fullPath == key }
                if (idx >= 0) Triple(idx, item, item.offset + item.size / 2) else null
            }.firstOrNull { (itemIndex, _, itemCenter) ->
                (itemIndex > draggedIndex && draggedCenter > itemCenter) ||
                    (itemIndex < draggedIndex && draggedCenter < itemCenter)
            }?.first
}

internal data class TagNode(
    val name: String,
    val fullPath: String,
    val count: Int? = null,
    val children: List<TagNode> = emptyList(),
)

internal fun buildTagTree(tags: List<SidebarTag>, rootOrder: List<String> = emptyList()): List<TagNode> {
    val rootNodes = mutableListOf<MutableTagNode>()
    val tagMap = tags.associate { it.name to it.count }

    tags.sortedBy { it.name }.forEach { tag ->
        insertTagPath(rootNodes, tag, tagMap)
    }

    val nodes = rootNodes.map { it.toImmutable() }
    if (rootOrder.isEmpty()) return nodes

    val orderIndex = rootOrder.withIndex().associate { (index, name) -> name to index }
    return nodes.sortedBy { node -> orderIndex[node.name] ?: (rootOrder.size + nodes.indexOf(node)) }
}

internal fun LazyListScope.sidebarTags(
    tags: List<SidebarTag>,
    tagTree: SnapshotStateList<TagNode>,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    selectedTagPath: String?,
    onTagClick: (String) -> Unit,
    anchorTagForPath: (String) -> String?,
    reorderState: TagReorderState,
    listState: LazyListState,
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

    items(tagTree, key = { it.fullPath }) { node ->
        val isDragged = reorderState.draggedKey == node.fullPath
        Box(
            modifier =
                Modifier
                    .then(
                        if (isDragged) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = reorderState.dragOffset }
                        } else {
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        },
                    )
                    .pointerInput(node.fullPath) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                reorderState.startDrag(node.fullPath)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                reorderState.updateDrag(dragAmount.y)
                                reorderState.checkAndSwap(
                                    tagTree = tagTree,
                                    listState = listState,
                                )
                            },
                            onDragEnd = {
                                reorderState.endDrag()
                                onReorderComplete(tagTree.map { it.name })
                            },
                            onDragCancel = {
                                reorderState.endDrag()
                            },
                        )
                    },
        ) {
            TagTreeItem(
                node = node,
                onTagClick = onTagClick,
                level = 0,
                expandedNodes = expandedNodes,
                selectedTagPath = selectedTagPath,
                onToggleExpand = { path -> expandedNodes[path] = !(expandedNodes[path] ?: false) },
                anchorTagForPath = anchorTagForPath,
            )
        }
    }
}

@Composable
private fun TagTreeItem(
    node: TagNode,
    onTagClick: (String) -> Unit,
    level: Int,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    selectedTagPath: String?,
    onToggleExpand: (String) -> Unit,
    anchorTagForPath: (String) -> String?,
) {
    val isExpanded = expandedNodes[node.fullPath] ?: false
    val hasChildren = node.children.isNotEmpty()

    NavigationDrawerItem(
        label = { TagTreeLabel(node) },
        icon = { TagTreeLeadingIcon() },
        badge = {
            TagTreeBadge(
                node = node,
                hasChildren = hasChildren,
                isExpanded = isExpanded,
                onToggleExpand = onToggleExpand,
            )
        },
        selected = selectedTagPath == node.fullPath,
        onClick = rememberTagClick(node.fullPath, onTagClick),
        colors =
            NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
            ),
        modifier =
            Modifier
                .height(48.dp)
                .padding(start = (level * AppSpacing.Medium.value).dp)
                .benchmarkAnchor(anchorTagForPath(node.fullPath)),
    )

    TagTreeChildren(
        node = node,
        isExpanded = isExpanded,
        onTagClick = onTagClick,
        level = level,
        expandedNodes = expandedNodes,
        selectedTagPath = selectedTagPath,
        onToggleExpand = onToggleExpand,
        anchorTagForPath = anchorTagForPath,
    )
}

@Composable
private fun TagTreeLabel(node: TagNode) {
    Text(
        node.name,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
    )
}

@Composable
private fun TagTreeLeadingIcon() {
    Icon(
        Icons.Outlined.Tag,
        null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TagTreeBadge(
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasChildren) {
            Spacer(modifier = Modifier.width(AppSpacing.Small))
            TagTreeExpandButton(
                isExpanded = isExpanded,
                onClick = { onToggleExpand(node.fullPath) },
            )
        }
    }
}

@Composable
private fun TagTreeExpandButton(
    isExpanded: Boolean,
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
        )
    }
}

@Composable
private fun TagTreeChildren(
    node: TagNode,
    isExpanded: Boolean,
    onTagClick: (String) -> Unit,
    level: Int,
    expandedNodes: SnapshotStateMap<String, Boolean>,
    selectedTagPath: String?,
    onToggleExpand: (String) -> Unit,
    anchorTagForPath: (String) -> String?,
) {
    AnimatedVisibility(
        visible = isExpanded,
        enter =
            expandVertically(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = com.lomo.ui.theme.MotionTokens.DurationMedium2,
                        easing = com.lomo.ui.theme.MotionTokens.EasingEmphasized,
                    ),
            ) +
                fadeIn(
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = com.lomo.ui.theme.MotionTokens.DurationMedium2,
                        ),
                ),
        exit =
            shrinkVertically(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = com.lomo.ui.theme.MotionTokens.DurationMedium2,
                        easing = com.lomo.ui.theme.MotionTokens.EasingEmphasized,
                    ),
            ) +
                fadeOut(
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = com.lomo.ui.theme.MotionTokens.DurationMedium2,
                        ),
                ),
    ) {
        Column {
            node.children.forEach { child ->
                TagTreeItem(
                    node = child,
                    onTagClick = onTagClick,
                    level = level + 1,
                    expandedNodes = expandedNodes,
                    selectedTagPath = selectedTagPath,
                    onToggleExpand = onToggleExpand,
                    anchorTagForPath = anchorTagForPath,
                )
            }
        }
    }
}

@Composable
private fun rememberTagClick(
    fullPath: String,
    onTagClick: (String) -> Unit,
): () -> Unit {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    return remember(fullPath, onTagClick, haptic) {
        {
            haptic.light()
            onTagClick(fullPath)
        }
    }
}

private fun insertTagPath(
    rootNodes: MutableList<MutableTagNode>,
    tag: SidebarTag,
    tagMap: Map<String, Int>,
) {
    var currentLevelNodes = rootNodes
    var currentPath = ""

    tag.name.split("/").forEachIndexed { index, part ->
        if (index > 0) {
            currentPath += "/"
        }
        currentPath += part

        val existingNode = currentLevelNodes.find { it.name == part }
        val node =
            existingNode ?: MutableTagNode(
                name = part,
                fullPath = currentPath,
                count = resolveTagNodeCount(currentPath, tag, tagMap),
            ).also(currentLevelNodes::add)

        if (currentPath == tag.name) {
            node.count = tag.count
        }
        currentLevelNodes = node.children
    }
}

private fun resolveTagNodeCount(
    currentPath: String,
    tag: SidebarTag,
    tagMap: Map<String, Int>,
): Int? = if (currentPath == tag.name) tag.count else tagMap[currentPath]

private class MutableTagNode(
    val name: String,
    val fullPath: String,
    var count: Int? = null,
    val children: MutableList<MutableTagNode> = mutableListOf(),
) {
    fun toImmutable(): TagNode = TagNode(name, fullPath, count, children.map { it.toImmutable() })
}
