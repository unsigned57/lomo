package com.lomo.ui.component.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.stats.CalendarHeatmap
import java.time.LocalDate

data class SidebarStats(
    val memoCount: Int = 0,
    val tagCount: Int = 0,
    val dayCount: Int = 0,
)

data class SidebarTag(
    val name: String,
    val count: Int = 0,
)

@Composable
fun SidebarDrawer(
    username: String,
    stats: SidebarStats,
    memoCountByDate: Map<LocalDate, Int>,
    tags: List<SidebarTag>,
    onMemoClick: () -> Unit = {},
    onTrashClick: () -> Unit = {},
    onDailyReviewClick: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val tagTree = remember(tags) { buildTagTree(tags) }
    val expandedNodes = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = username,
                    style = MaterialTheme.typography.titleLarge,
                )

                IconButton(
                    onClick = {
                        haptic.medium()
                        onSettingsClick()
                    },
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.sidebar_settings),
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Stats row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(
                    value = stats.memoCount.toString(),
                    label =
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.stat_memo),
                )
                StatItem(
                    value = stats.tagCount.toString(),
                    label =
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.stat_tag),
                )
                StatItem(
                    value = stats.dayCount.toString(),
                    label =
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.stat_day),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Heatmap
        item {
            CalendarHeatmap(
                memoCountByDate = memoCountByDate,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // MEMO (selected)
        item {
            NavigationItem(
                icon = Icons.Filled.Dashboard, // Filled for selected state
                label =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.ui.R.string.sidebar_memo),
                isSelected = true,
                onClick = onMemoClick,
            )
        }

        // Trash (unselected)
        item {
            NavigationItem(
                icon = Icons.Outlined.Delete, // Outlined for unselected state
                label =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.ui.R.string.sidebar_trash),
                onClick = onTrashClick,
            )
        }

        // Daily Review
        item {
            NavigationItem(
                icon = Icons.Outlined.DateRange,
                label =
                    androidx.compose.ui.res
                        .stringResource(com.lomo.ui.R.string.sidebar_daily_review),
                onClick = onDailyReviewClick,
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Tags
        if (tags.isNotEmpty()) {
            item {
                Text(
                    text =
                        androidx.compose.ui.res
                            .stringResource(com.lomo.ui.R.string.sidebar_tags),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            items(tagTree) { node ->
                TagTreeItem(
                    node = node,
                    onTagClick = onTagClick,
                    level = 0,
                    expandedNodes = expandedNodes,
                    onToggleExpand = { path ->
                        expandedNodes[path] = !(expandedNodes[path] ?: false)
                    },
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun NavigationItem(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        icon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
        badge = badge?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        selected = isSelected,
        onClick = {
            haptic.light()
            onClick()
        },
        colors =
            NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
            ),
        modifier = Modifier.height(48.dp),
    )
}

@Composable
private fun TagTreeItem(
    node: TagNode,
    onTagClick: (String) -> Unit,
    level: Int,
    expandedNodes: Map<String, Boolean>,
    onToggleExpand: (String) -> Unit,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val isExpanded = expandedNodes[node.fullPath] ?: false
    val hasChildren = node.children.isNotEmpty()

    // Indentation for hierarchy (16.dp per level)
    val leadingIcon = Icons.Outlined.Tag

    NavigationDrawerItem(
        label = {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        },
        icon = {
            Icon(
                leadingIcon,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.count != null) {
                    Text(
                        node.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (hasChildren) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription =
                            androidx.compose.ui.res.stringResource(
                                if (isExpanded) com.lomo.ui.R.string.cd_collapse else com.lomo.ui.R.string.cd_expand,
                            ),
                        modifier =
                            Modifier
                                .size(20.dp)
                                .clickable {
                                    haptic.light()
                                    onToggleExpand(node.fullPath)
                                },
                    )
                }
            }
        },
        selected = false,
        onClick = {
            haptic.light()
            onTagClick(node.fullPath)
        },
        colors =
            NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
            ),
        modifier =
            Modifier
                .height(48.dp)
                .padding(start = (level * 16).dp),
    )

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
                androidx.compose.animation.fadeIn(
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
                androidx.compose.animation.fadeOut(
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
                    onToggleExpand = onToggleExpand,
                )
            }
        }
    }
}

private data class TagNode(
    val name: String,
    val fullPath: String,
    val count: Int? = null,
    val children: List<TagNode> = emptyList(),
)

private fun buildTagTree(tags: List<SidebarTag>): List<TagNode> {
    val rootNodes = mutableListOf<MutableTagNode>()
    val tagMap = tags.associate { it.name to it.count }

    // Sort tags to ensure parents typically come before children or are handled consistently
    tags.sortedBy { it.name }.forEach { tag ->
        val parts = tag.name.split("/")
        var currentLevelNodes = rootNodes
        var currentPath = ""

        parts.forEachIndexed { index, part ->
            if (index > 0) currentPath += "/"
            currentPath += part

            var node = currentLevelNodes.find { it.name == part }
            if (node == null) {
                // If this is the exact tag we are processing, use its count.
                // Otherwise only set count if this intermediate path exists as a standalone tag.
                val nodeCount = if (currentPath == tag.name) tag.count else tagMap[currentPath]

                node = MutableTagNode(part, currentPath, nodeCount)
                currentLevelNodes.add(node)
            } else {
                // Update count if we found a node that was previously created as a parent but now we are processing the specific tag
                if (currentPath == tag.name) {
                    node.count = tag.count
                }
            }
            currentLevelNodes = node.children
        }
    }

    return rootNodes.map { it.toImmutable() }
}

private class MutableTagNode(
    val name: String,
    val fullPath: String,
    var count: Int? = null,
    val children: MutableList<MutableTagNode> = mutableListOf(),
) {
    fun toImmutable(): TagNode = TagNode(name, fullPath, count, children.map { it.toImmutable() })
}
