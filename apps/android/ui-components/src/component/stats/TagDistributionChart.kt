package com.lomo.ui.component.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.collections.immutable.ImmutableList

internal const val MAX_DISPLAY_TAGS = 10
private const val TAG_NAME_WEIGHT = 0.25f
private const val TAG_BAR_WEIGHT = 0.65f
private const val TAG_COUNT_WEIGHT = 0.1f

data class ChartTagSlice(val name: String, val count: Int)

internal data class ResolvedTagDistributionBar(val slice: ChartTagSlice, val fraction: Float)

internal fun resolveTagDistributionBars(slices: Iterable<ChartTagSlice>): List<ResolvedTagDistributionBar> {
    val topSlices =
        slices
            .sortedByDescending { it.count }
            .take(MAX_DISPLAY_TAGS)
    val maxCount = topSlices.maxOfOrNull { it.count.coerceAtLeast(0) }?.coerceAtLeast(1) ?: 1
    return topSlices.map { slice ->
        ResolvedTagDistributionBar(
            slice = slice,
            fraction = slice.count.coerceAtLeast(0).toFloat() / maxCount,
        )
    }
}

@Composable
fun TagDistributionChart(slices: ImmutableList<ChartTagSlice>, modifier: Modifier = Modifier) {
    val bars = remember(slices) {
        resolveTagDistributionBars(slices)
    }
    val barColor = MaterialTheme.colorScheme.primary
    val componentMotion = LocalLomoMotion.current.component

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        for (bar in bars) {
            val animatedFraction by animateFloatAsState(
                targetValue = bar.fraction,
                animationSpec = componentMotion.defaultSpatialSpec(),
                label = "TagDistributionBarFraction",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                Text(
                    text = "#${bar.slice.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(TAG_NAME_WEIGHT),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .weight(TAG_BAR_WEIGHT)
                        .height(StatsChartTokens.TagBarHeight)
                        .clip(AppShapes.Small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction)
                            .height(StatsChartTokens.TagBarHeight)
                            .clip(AppShapes.Small)
                            .background(
                                StatsChartTokens.tagBarColor(
                                    colorScheme = MaterialTheme.colorScheme,
                                    baseColor = barColor,
                                    fraction = bar.fraction,
                                ),
                            ),
                    )
                }
                Text(
                    text = bar.slice.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(TAG_COUNT_WEIGHT),
                )
            }
        }
    }
}
