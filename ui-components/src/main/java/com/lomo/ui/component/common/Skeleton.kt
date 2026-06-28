package com.lomo.ui.component.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.AppShapes

private const val DEFAULT_SHIMMER_TARGET = 1000f
private const val SHIMMER_DURATION_MILLIS = 800
private const val PRIMARY_CONTENT_WIDTH_FRACTION = 0.9f
private const val SECONDARY_CONTENT_WIDTH_FRACTION = 0.6f
private const val SKELETON_ITEM_COUNT = 5

@Composable
fun shimmerBrush(
    showShimmer: Boolean = true,
    targetValue: Float = DEFAULT_SHIMMER_TARGET,
): Brush =
    if (showShimmer) {
        val shimmerColors =
            listOf(
                SkeletonTokens.shimmerHighlightColor(MaterialTheme.colorScheme),
                MaterialTheme.colorScheme.surfaceContainerHighest,
                SkeletonTokens.shimmerHighlightColor(MaterialTheme.colorScheme),
            )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation by
            transition.animateFloat(
                initialValue = 0f,
                targetValue = targetValue,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(SHIMMER_DURATION_MILLIS),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "shimmer",
            )
        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation, y = translateAnimation),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent),
            start = Offset.Zero,
            end = Offset.Zero,
        )
    }

@Composable
fun SkeletonMemoItem(
    modifier: Modifier = Modifier,
    brush: Brush = shimmerBrush(),
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Medium, // Match MemoCard shape
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(SkeletonTokens.ItemContentPadding),
            verticalArrangement = Arrangement.spacedBy(SkeletonTokens.ItemVerticalSpacing),
        ) {
            // Header (Time + Tag)
            Row(horizontalArrangement = Arrangement.spacedBy(SkeletonTokens.HeaderSpacing)) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 80.dp, height = 14.dp)
                            .clip(SkeletonTokens.LineShape)
                            .background(brush),
                )
            }

            // Content Lines
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(PRIMARY_CONTENT_WIDTH_FRACTION)
                        .height(16.dp)
                        .clip(SkeletonTokens.LineShape)
                        .background(brush),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(SECONDARY_CONTENT_WIDTH_FRACTION)
                        .height(16.dp)
                        .clip(SkeletonTokens.LineShape)
                        .background(brush),
            )

            // Footer (Actions)
            Row(
                modifier = Modifier.padding(top = SkeletonTokens.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(SkeletonTokens.FooterSpacing),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(SkeletonTokens.ActionShape)
                            .background(brush),
                )
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(SkeletonTokens.ActionShape)
                            .background(brush),
                )
            }
        }
    }
}

@Composable
fun MemoListSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(
        modifier = modifier.padding(SkeletonTokens.ItemContentPadding),
        verticalArrangement = Arrangement.spacedBy(SkeletonTokens.ItemVerticalSpacing),
    ) {
        repeat(SKELETON_ITEM_COUNT) { SkeletonMemoItem(brush = brush) }
    }
}
