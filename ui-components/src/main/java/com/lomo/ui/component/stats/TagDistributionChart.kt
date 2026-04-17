package com.lomo.ui.component.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.MemoTagCount
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList

private val BAR_HEIGHT = 20.dp
private const val MAX_DISPLAY_TAGS = 10
private const val TAG_NAME_WEIGHT = 0.25f
private const val TAG_BAR_WEIGHT = 0.65f
private const val TAG_COUNT_WEIGHT = 0.1f
private const val BAR_ALPHA_BASE = 0.3f
private const val BAR_ALPHA_RANGE = 0.7f

@Composable
fun TagDistributionChart(
    tagCounts: ImmutableList<MemoTagCount>,
    modifier: Modifier = Modifier,
) {
    val topTags = remember(tagCounts) {
        tagCounts
            .sortedByDescending { it.count }
            .take(MAX_DISPLAY_TAGS)
    }
    val maxCount = remember(topTags) {
        topTags.maxOfOrNull { it.count } ?: 1
    }
    val barColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        for (tag in topTags) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(TAG_NAME_WEIGHT),
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .weight(TAG_BAR_WEIGHT)
                        .height(BAR_HEIGHT),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val fraction = tag.count.toFloat() / maxCount
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(BAR_HEIGHT)
                            .clip(AppShapes.ExtraSmall)
                            .background(barColor.copy(alpha = BAR_ALPHA_BASE + BAR_ALPHA_RANGE * fraction)),
                    )
                }
                Text(
                    text = tag.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(TAG_COUNT_WEIGHT),
                )
            }
        }
    }
}
