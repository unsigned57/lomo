package com.lomo.ui.component.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun ShimmerBrush(
    showShimmer: Boolean = true,
    targetValue: Float = 1000f,
): Brush =
    if (showShimmer) {
        val shimmerColors =
            listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation by
            transition.animateFloat(
                initialValue = 0f,
                targetValue = targetValue,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(800),
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
fun SkeletonMemoItem(modifier: Modifier = Modifier) {
    val brush = ShimmerBrush()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Medium, // Match MemoCard shape
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header (Time + Tag)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 80.dp, height = 14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush),
                )
            }

            // Content Lines
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.9f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush),
            )

            // Footer (Actions)
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(brush),
                )
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(brush),
                )
            }
        }
    }
}

@Composable
fun MemoListSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(5) { SkeletonMemoItem() }
    }
}
