package com.lomo.ui.component.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.stats.CalendarHeatmap
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LomoTheme
import java.time.LocalDate

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

    data class Tag(
        val fullPath: String,
    ) : SidebarDestination
}

@Composable
fun SidebarDrawer(
    username: String,
    stats: SidebarStats,
    memoCountByDate: Map<LocalDate, Int>,
    tags: List<SidebarTag>,
    modifier: Modifier = Modifier,
    currentDestination: SidebarDestination = SidebarDestination.Memo,
    onMemoClick: () -> Unit = {},
    onTrashClick: () -> Unit = {},
    onDailyReviewClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    onHeatmapDateLongPress: (LocalDate) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    settingsAnchorTag: String? = null,
    trashAnchorTag: String? = null,
    tagAnchorForPath: (String) -> String? = { null },
) {
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val tagTree = remember(tags) { buildTagTree(tags) }
    val expandedNodes = remember { mutableStateMapOf<String, Boolean>() }
    val selectedTagPath = (currentDestination as? SidebarDestination.Tag)?.fullPath
    val isMemoSelected = currentDestination == SidebarDestination.Memo
    val isTrashSelected = currentDestination == SidebarDestination.Trash
    val isDailyReviewSelected = currentDestination == SidebarDestination.DailyReview
    val isGallerySelected = currentDestination == SidebarDestination.Gallery

    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        sidebarHeader(
            username = username,
            onSettingsClick = onSettingsClick,
            settingsAnchorTag = settingsAnchorTag,
        )
        sidebarStats(stats = stats)
        sidebarHeatmap(
            memoCountByDate = memoCountByDate,
            onDateLongPress = onHeatmapDateLongPress,
        )
        sidebarDestinations(
            isMemoSelected = isMemoSelected,
            isTrashSelected = isTrashSelected,
            isDailyReviewSelected = isDailyReviewSelected,
            isGallerySelected = isGallerySelected,
            onMemoClick = onMemoClick,
            onTrashClick = onTrashClick,
            onDailyReviewClick = onDailyReviewClick,
            onGalleryClick = onGalleryClick,
            trashAnchorTag = trashAnchorTag,
        )
        sidebarTags(
            tags = tags,
            tagTree = tagTree,
            expandedNodes = expandedNodes,
            selectedTagPath = selectedTagPath,
            onTagClick = onTagClick,
            anchorTagForPath = tagAnchorForPath,
        )
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
    anchorTag: String? = null,
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
        modifier = Modifier.height(48.dp).benchmarkAnchor(anchorTag),
    )
}

private fun LazyListScope.sidebarHeader(
    username: String,
    onSettingsClick: () -> Unit,
    settingsAnchorTag: String?,
) {
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
                onClick = rememberMediumHapticClick(onSettingsClick),
                modifier = Modifier.benchmarkAnchor(settingsAnchorTag),
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.sidebar_settings),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.Large))
    }
}

private fun LazyListScope.sidebarStats(stats: SidebarStats) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem(
                value = stats.memoCount.toString(),
                label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.stat_memo),
            )
            StatItem(
                value = stats.tagCount.toString(),
                label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.stat_tag),
            )
            StatItem(
                value = stats.dayCount.toString(),
                label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.stat_day),
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
    }
}

private fun LazyListScope.sidebarHeatmap(
    memoCountByDate: Map<LocalDate, Int>,
    onDateLongPress: (LocalDate) -> Unit,
) {
    item {
        CalendarHeatmap(
            memoCountByDate = memoCountByDate,
            onDateLongPress = onDateLongPress,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(AppSpacing.Medium))
    }
}

private fun LazyListScope.sidebarDestinations(
    isMemoSelected: Boolean,
    isTrashSelected: Boolean,
    isDailyReviewSelected: Boolean,
    isGallerySelected: Boolean,
    onMemoClick: () -> Unit,
    onTrashClick: () -> Unit,
    onDailyReviewClick: () -> Unit,
    onGalleryClick: () -> Unit,
    trashAnchorTag: String?,
) {
    item {
        NavigationItem(
            icon = if (isMemoSelected) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
            label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.sidebar_memo),
            isSelected = isMemoSelected,
            onClick = onMemoClick,
        )
    }
    item {
        NavigationItem(
            icon = if (isTrashSelected) Icons.Filled.Delete else Icons.Outlined.Delete,
            label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.sidebar_trash),
            isSelected = isTrashSelected,
            anchorTag = trashAnchorTag,
            onClick = onTrashClick,
        )
    }
    item {
        NavigationItem(
            icon = if (isDailyReviewSelected) Icons.Filled.DateRange else Icons.Outlined.DateRange,
            label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.sidebar_daily_review),
            isSelected = isDailyReviewSelected,
            onClick = onDailyReviewClick,
        )
    }
    item {
        NavigationItem(
            icon = Icons.Outlined.PhotoLibrary,
            label = androidx.compose.ui.res.stringResource(com.lomo.ui.R.string.sidebar_gallery),
            isSelected = isGallerySelected,
            onClick = onGalleryClick,
        )
    }
    item { HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.Small)) }
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
    val today = LocalDate.now()
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
            username = "Lomo",
            stats = sampleStats,
            memoCountByDate = memoCountByDate,
            tags = sampleTags,
            currentDestination = SidebarDestination.Memo,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun SidebarDrawerPreviewTagSelected() {
    val today = LocalDate.now()
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
            username = "Lomo",
            stats =
                SidebarStats(
                    memoCount = PREVIEW_TAG_SELECTED_MEMO_COUNT,
                    tagCount = PREVIEW_TAG_SELECTED_TAG_COUNT,
                    dayCount = PREVIEW_TAG_SELECTED_DAY_COUNT,
                ),
            memoCountByDate = memoCountByDate,
            tags = sampleTags,
            currentDestination = SidebarDestination.Tag("project/android"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
