package com.lomo.ui.component.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.stats.CalendarHeatmap
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LomoTheme
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import java.time.LocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

private const val PREVIEW_MEMO_COUNT = 196
private const val PREVIEW_TAG_COUNT = 14
private const val PREVIEW_DAY_COUNT = 88
private const val PREVIEW_WORK_TAG_COUNT = 42
private const val PREVIEW_WORK_ROADMAP_TAG_COUNT = 8
private const val PREVIEW_WORK_RETRO_TAG_COUNT = 5
private const val PREVIEW_PERSONAL_TAG_COUNT = 39
private const val PREVIEW_PERSONAL_BOOKS_TAG_COUNT = 12
private const val PREVIEW_TRAVEL_TAG_COUNT = 11
private const val PREVIEW_MEMO_RANGE_DAYS = 90
private const val PREVIEW_HEATMAP_DIVISOR_LARGE = 13
private const val PREVIEW_HEATMAP_VALUE_LARGE = 7
private const val PREVIEW_HEATMAP_DIVISOR_MEDIUM = 6
private const val PREVIEW_HEATMAP_VALUE_MEDIUM = 4
private const val PREVIEW_HEATMAP_DIVISOR_SMALL = 3
private const val PREVIEW_HEATMAP_VALUE_SMALL = 2
private const val PREVIEW_TAG_SELECTED_PROJECT_COUNT = 22
private const val PREVIEW_TAG_SELECTED_ANDROID_COUNT = 9
private const val PREVIEW_TAG_SELECTED_WAVE4_COUNT = 4
private const val PREVIEW_TAG_SELECTED_JOURNAL_COUNT = 17
private const val PREVIEW_TAG_SELECTED_RANGE_DAYS = 45
private const val PREVIEW_TAG_SELECTED_EVEN_DIVISOR = 2
private const val PREVIEW_TAG_SELECTED_BUCKET_DIVISOR = 5
private const val PREVIEW_TAG_SELECTED_BUCKET_OFFSET = 1
private const val PREVIEW_TAG_SELECTED_MEMO_COUNT = 94
private const val PREVIEW_TAG_SELECTED_TAG_COUNT = 7
private const val PREVIEW_TAG_SELECTED_DAY_COUNT = 36
private val PREVIEW_TODAY: LocalDate = LocalDate.of(2025, 1, 15)

data class SidebarStats(
    val memoCount: Int = 0,
    val tagCount: Int = 0,
    val dayCount: Int = 0,
)

data class SidebarTag(
    val name: String,
    val count: Int = 0,
)

sealed interface SidebarDestination {
    data object Memo : SidebarDestination

    data object Trash : SidebarDestination

    data object DailyReview : SidebarDestination

    data object Gallery : SidebarDestination

    data object Statistics : SidebarDestination

    data class Tag(
        val fullPath: String,
    ) : SidebarDestination
}

@Composable
fun SidebarDrawer(
    stats: SidebarStats,
    memoCountByDate: ImmutableMap<LocalDate, Int>,
    today: LocalDate,
    tags: ImmutableList<SidebarTag>,
    calendarHeatmapThresholds: CalendarHeatmapThresholds,
    modifier: Modifier = Modifier,
    rootTagOrder: ImmutableList<String> = kotlinx.collections.immutable.persistentListOf(),
    currentDestination: SidebarDestination = SidebarDestination.Memo,
    onTrashClick: () -> Unit = {},
    onDailyReviewClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {},
    onStatisticsClick: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onTagReorder: (List<String>) -> Unit = {},
    onHeatmapDateLongPress: (LocalDate) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    settingsAnchorTag: String? = null,
    trashAnchorTag: String? = null,
    tagAnchorForPath: (String) -> String? = { null },
) {
    val tagTree = remember(tags, rootTagOrder) { buildTagTree(tags, rootTagOrder) }
    val reorderableTree: SnapshotStateList<TagNode> =
        remember(tagTree) { tagTree.toMutableStateList() }
    val expandedNodes = remember { mutableStateMapOf<String, Boolean>() }
    val selectedTagPath = (currentDestination as? SidebarDestination.Tag)?.fullPath
    val isTrashSelected = currentDestination == SidebarDestination.Trash
    val isDailyReviewSelected = currentDestination == SidebarDestination.DailyReview
    val isGallerySelected = currentDestination == SidebarDestination.Gallery
    val isStatisticsSelected = currentDestination == SidebarDestination.Statistics
    val listState = rememberLazyListState()
    val reorderableLazyListState =
        sh.calvin.reorderable.rememberReorderableLazyListState(listState) { from, to ->
            applyReorderableTagMove(
                tagTree = reorderableTree,
                fromKey = from.key,
                toKey = to.key,
            )
        }
    val expandedNodePaths = expandedNodes.filterValues { it }.keys
    val visibleTagRows = visibleTagRows(reorderableTree, expandedNodePaths)


    Column(
        modifier = modifier.fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                contentPadding = PaddingValues(
                    start = AppSpacing.Medium,
                    end = AppSpacing.Medium,
                    top = AppSpacing.Medium,
                    bottom = AppSpacing.ExtraLarge
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                sidebarStatusHub(
                    stats = stats,
                    memoCountByDate = memoCountByDate,
                    today = today,
                    thresholds = calendarHeatmapThresholds,
                    onDateLongPress = onHeatmapDateLongPress,
                )
                sidebarDestinations(
                    isDailyReviewSelected = isDailyReviewSelected,
                    isGallerySelected = isGallerySelected,
                    isStatisticsSelected = isStatisticsSelected,
                    onDailyReviewClick = onDailyReviewClick,
                    onGalleryClick = onGalleryClick,
                    onStatisticsClick = onStatisticsClick,
                )
                sidebarTags(
                    tags = tags,
                    visibleRows = visibleTagRows,
                    tagTree = reorderableTree,
                    expandedNodes = expandedNodes,
                    selectedTagPath = selectedTagPath,
                    onTagClick = onTagClick,
                    anchorTagForPath = tagAnchorForPath,
                    reorderableLazyListState = reorderableLazyListState,

                    onReorderComplete = onTagReorder,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(32.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                SidebarDrawerTokens.Footer
                                    .cardContainerColor(MaterialTheme.colorScheme)
                            )
                        )
                    )
            )
        }

        SidebarFooter(
            isTrashSelected = isTrashSelected,
            onTrashClick = onTrashClick,
            onSettingsClick = onSettingsClick,
            trashAnchorTag = trashAnchorTag,
            settingsAnchorTag = settingsAnchorTag
        )
    }
}

@Composable
private fun SidebarFooter(
    isTrashSelected: Boolean,
    onTrashClick: () -> Unit,
    onSettingsClick: () -> Unit,
    trashAnchorTag: String?,
    settingsAnchorTag: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.Medium,
                end = AppSpacing.Medium,
                bottom = AppSpacing.Medium,
                top = AppSpacing.Small
            ),
        shape = SidebarDrawerTokens.Footer.CardShape,
        color = SidebarDrawerTokens.Footer.cardContainerColor(MaterialTheme.colorScheme),
        border = BorderStroke(
            width = 1.dp,
            color = SidebarDrawerTokens.Footer.cardBorderColor(MaterialTheme.colorScheme)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = AppSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UtilityButton(
                icon = if (isTrashSelected) Icons.Filled.Delete else Icons.Outlined.Delete,
                label = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_trash),
                isSelected = isTrashSelected,
                anchorTag = trashAnchorTag,
                onClick = onTrashClick,
                selectedContainerColor = SidebarDrawerTokens
                    .trashSelectedContainerColor(MaterialTheme.colorScheme),
                selectedContentColor = SidebarDrawerTokens
                    .trashSelectedContentColor(MaterialTheme.colorScheme),
                unselectedContainerColor = SidebarDrawerTokens
                    .trashUnselectedContainerColor(MaterialTheme.colorScheme),
                unselectedContentColor = SidebarDrawerTokens
                    .trashUnselectedContentColor(MaterialTheme.colorScheme),
                modifier = Modifier.weight(1f)
            )
            UtilityButton(
                icon = Icons.Rounded.Settings,
                label = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_settings),
                isSelected = false,
                anchorTag = settingsAnchorTag,
                onClick = onSettingsClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}



@Composable
private fun UtilityButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    anchorTag: String? = null,
    shape: androidx.compose.ui.graphics.Shape = SidebarDrawerTokens.Footer.ButtonShape,
    selectedContainerColor: Color =
        SidebarDrawerTokens.selectedContainerColor(MaterialTheme.colorScheme),
    selectedContentColor: Color = MaterialTheme.colorScheme.primary,
    unselectedContainerColor: Color =
        SidebarDrawerTokens.cardContainerColor(MaterialTheme.colorScheme),
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    Surface(
        onClick = {
            haptic.light()
            onClick()
        },
        shape = shape,
        color = if (isSelected) {
            selectedContainerColor
        } else {
            unselectedContainerColor
        },
        contentColor = if (isSelected) {
            selectedContentColor
        } else {
            unselectedContentColor
        },
        modifier = modifier.height(38.dp).benchmarkAnchor(anchorTag)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.Small),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.Small))
            Text(
                text = label,
                color = androidx.compose.material3.LocalContentColor.current,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun NavigationItem(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    isSelected: Boolean = false,
    anchorTag: String? = null,
    onClick: () -> Unit,
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SidebarDrawerTokens.RowHeight)
            .benchmarkAnchor(anchorTag),
        contentAlignment = Alignment.CenterStart
    ) {
        NavigationDrawerItem(
            label = {
                Text(
                    text = label,
                    style = if (isSelected) {
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    }
                )
            },
            icon = { Icon(icon, null, modifier = Modifier.size(SidebarDrawerTokens.NavigationIconSize)) },
            badge = badge?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
            selected = isSelected,
            onClick = {
                haptic.light()
                onClick()
            },
            colors =
                NavigationDrawerItemDefaults.colors(
                    selectedContainerColor =
                        SidebarDrawerTokens.selectedContainerColor(
                            MaterialTheme.colorScheme
                        ),
                    unselectedContainerColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            shape = SidebarDrawerTokens.NavigationItemShape,
            modifier = Modifier.fillMaxSize(),
        )

    }
}


@Composable
private fun StatIndicator(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = AppSpacing.Small, horizontal = AppSpacing.ExtraSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SidebarDrawerTokens.statCardLabelColor(MaterialTheme.colorScheme)
        )
    }
}

private fun LazyListScope.sidebarStatusHub(
    stats: SidebarStats,
    memoCountByDate: ImmutableMap<LocalDate, Int>,
    today: LocalDate,
    thresholds: CalendarHeatmapThresholds,
    onDateLongPress: (LocalDate) -> Unit,
) {
    item {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.ExtraSmall),
            shape = SidebarDrawerTokens.HubCardShape,
            color = SidebarDrawerTokens.cardContainerColor(MaterialTheme.colorScheme),
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)
            ) {
                CalendarHeatmap(
                    memoCountByDate = memoCountByDate,
                    today = today,
                    thresholds = thresholds,
                    onDateLongPress = onDateLongPress,
                    yearBackgroundColor = SidebarDrawerTokens.heatmapYearBackgroundColor(MaterialTheme.colorScheme),
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.Large,
                    color = SidebarDrawerTokens.statRowContainerColor(MaterialTheme.colorScheme)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatIndicator(
                            value = stats.memoCount.toString(),
                            label = org.jetbrains.compose.resources.stringResource(Res.string.stat_memo),
                            modifier = Modifier.weight(1f)
                        )
                        VerticalDivider(
                            color = SidebarDrawerTokens.dividerColor(MaterialTheme.colorScheme),
                            modifier = Modifier.height(24.dp).width(1.dp)
                        )
                        StatIndicator(
                            value = stats.tagCount.toString(),
                            label = org.jetbrains.compose.resources.stringResource(Res.string.stat_tag),
                            modifier = Modifier.weight(1f)
                        )
                        VerticalDivider(
                            color = SidebarDrawerTokens.dividerColor(MaterialTheme.colorScheme),
                            modifier = Modifier.height(24.dp).width(1.dp)
                        )
                        StatIndicator(
                            value = stats.dayCount.toString(),
                            label = org.jetbrains.compose.resources.stringResource(Res.string.stat_day),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
    }
}

private fun LazyListScope.sidebarDestinations(
    isDailyReviewSelected: Boolean,
    isGallerySelected: Boolean,
    isStatisticsSelected: Boolean,
    onDailyReviewClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onStatisticsClick: () -> Unit,
) {
    item {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.Large,
            color = SidebarDrawerTokens.cardContainerColor(MaterialTheme.colorScheme)
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)
            ) {
                NavigationItem(
                    icon = if (isDailyReviewSelected) Icons.Filled.DateRange else Icons.Outlined.DateRange,
                    label = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_daily_review),
                    isSelected = isDailyReviewSelected,
                    onClick = onDailyReviewClick,
                )
                NavigationItem(
                    icon = Icons.Outlined.PhotoLibrary,
                    label = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_gallery),
                    isSelected = isGallerySelected,
                    onClick = onGalleryClick,
                )
                NavigationItem(
                    icon = Icons.Outlined.Analytics,
                    label = org.jetbrains.compose.resources.stringResource(Res.string.sidebar_statistics),
                    isSelected = isStatisticsSelected,
                    onClick = onStatisticsClick,
                )
            }
        }
    }
}

@Composable
internal fun rememberLightHapticClick(onClick: () -> Unit): () -> Unit {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    return remember(onClick, haptic) {
        {
            haptic.light()
            onClick()
        }
    }
}

@Composable
private fun rememberMediumHapticClick(onClick: () -> Unit): () -> Unit {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    return remember(onClick, haptic) {
        {
            haptic.medium()
            onClick()
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SidebarDrawerPreviewMemo() {
    val today = PREVIEW_TODAY
    val sampleStats =
        SidebarStats(
            memoCount = PREVIEW_MEMO_COUNT,
            tagCount = PREVIEW_TAG_COUNT,
            dayCount = PREVIEW_DAY_COUNT,
        )
    val sampleTags =
        listOf(
            SidebarTag("work", PREVIEW_WORK_TAG_COUNT),
            SidebarTag("work/roadmap", PREVIEW_WORK_ROADMAP_TAG_COUNT),
            SidebarTag("work/retro", PREVIEW_WORK_RETRO_TAG_COUNT),
            SidebarTag("personal", PREVIEW_PERSONAL_TAG_COUNT),
            SidebarTag("personal/books", PREVIEW_PERSONAL_BOOKS_TAG_COUNT),
            SidebarTag("travel", PREVIEW_TRAVEL_TAG_COUNT),
        )
    val memoCountByDate =
        buildMap {
            for (index in 0..PREVIEW_MEMO_RANGE_DAYS) {
                val date = today.minusDays(index.toLong())
                val count =
                    when {
                        index % PREVIEW_HEATMAP_DIVISOR_LARGE == 0 -> PREVIEW_HEATMAP_VALUE_LARGE
                        index % PREVIEW_HEATMAP_DIVISOR_MEDIUM == 0 -> PREVIEW_HEATMAP_VALUE_MEDIUM
                        index % PREVIEW_HEATMAP_DIVISOR_SMALL == 0 -> PREVIEW_HEATMAP_VALUE_SMALL
                        else -> 0
                    }
                if (count > 0) put(date, count)
            }
        }

    LomoTheme {
        SidebarDrawer(
            stats = sampleStats,
            memoCountByDate = memoCountByDate.toImmutableMap(),
            today = today,
            tags = sampleTags.toImmutableList(),
            calendarHeatmapThresholds = CalendarHeatmapThresholds.default(),
            currentDestination = SidebarDestination.Memo,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SidebarDrawerPreviewTagSelected() {
    val today = PREVIEW_TODAY
    val sampleTags =
        listOf(
            SidebarTag("project", PREVIEW_TAG_SELECTED_PROJECT_COUNT),
            SidebarTag("project/android", PREVIEW_TAG_SELECTED_ANDROID_COUNT),
            SidebarTag("project/android/wave4", PREVIEW_TAG_SELECTED_WAVE4_COUNT),
            SidebarTag("journal", PREVIEW_TAG_SELECTED_JOURNAL_COUNT),
        )
    val memoCountByDate =
        buildMap {
            for (index in 0..PREVIEW_TAG_SELECTED_RANGE_DAYS) {
                val date = today.minusDays(index.toLong())
                if (index % PREVIEW_TAG_SELECTED_EVEN_DIVISOR == 0) {
                    put(
                        date,
                        (index % PREVIEW_TAG_SELECTED_BUCKET_DIVISOR) + PREVIEW_TAG_SELECTED_BUCKET_OFFSET,
                    )
                }
            }
        }

    LomoTheme {
        SidebarDrawer(
            stats =
                SidebarStats(
                    memoCount = PREVIEW_TAG_SELECTED_MEMO_COUNT,
                    tagCount = PREVIEW_TAG_SELECTED_TAG_COUNT,
                    dayCount = PREVIEW_TAG_SELECTED_DAY_COUNT,
                ),
            memoCountByDate = memoCountByDate.toImmutableMap(),
            today = today,
            tags = sampleTags.toImmutableList(),
            calendarHeatmapThresholds = CalendarHeatmapThresholds.default(),
            currentDestination = SidebarDestination.Tag("project/android"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
