package com.lomo.app.feature.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.formatAsDateTime
import kotlin.math.roundToInt

private val GALLERY_REEL_COLLAPSED_HEIGHT = 200.dp
private const val GALLERY_REEL_EXPANDED_HEIGHT_FRACTION = 0.8f
private const val GALLERY_REEL_GRADIENT_HEIGHT_FRACTION = 0.42f
private const val GALLERY_REEL_HANDLE_WIDTH_FRACTION = 0.14f

@Composable
fun GalleryReelMemoOverlay(
    memo: MemoUiModel,
    draggableState: AnchoredDraggableState<GalleryReelOverlayAnchor>,
    dateFormat: String,
    timeFormat: String,
    onShowMoreMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val collapsedHeightPx = with(density) { GALLERY_REEL_COLLAPSED_HEIGHT.toPx() }
        val expandedHeightPx = fullHeightPx * GALLERY_REEL_EXPANDED_HEIGHT_FRACTION

        LaunchedEffect(fullHeightPx, collapsedHeightPx, expandedHeightPx) {
            draggableState.updateAnchors(
                DraggableAnchors {
                    GalleryReelOverlayAnchor.Hidden at fullHeightPx
                    GalleryReelOverlayAnchor.Collapsed at (fullHeightPx - collapsedHeightPx).coerceAtLeast(0f)
                    GalleryReelOverlayAnchor.Expanded at (fullHeightPx - expandedHeightPx).coerceAtLeast(0f)
                },
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(GALLERY_REEL_GRADIENT_HEIGHT_FRACTION)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight)
                    .offset {
                        val offset = draggableState.offset.takeIf { it.isFinite() } ?: fullHeightPx
                        IntOffset(x = 0, y = offset.roundToInt())
                    }
                    .anchoredDraggable(
                        state = draggableState,
                        orientation = Orientation.Vertical,
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
        ) {
            GalleryReelOverlayHandle()
            Crossfade(
                targetState = draggableState.targetValue,
                label = "GalleryReelOverlayContent",
            ) { anchor ->
                if (anchor == GalleryReelOverlayAnchor.Expanded) {
                    GalleryReelExpandedMemo(
                        memo = memo,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        onShowMoreMenu = onShowMoreMenu,
                    )
                } else {
                    GalleryReelCollapsedMemo(
                        memo = memo,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryReelOverlayHandle() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = AppSpacing.Small),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth(GALLERY_REEL_HANDLE_WIDTH_FRACTION)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.42f)),
        )
    }
}

@Composable
private fun GalleryReelCollapsedMemo(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = memo.collapsedSummary.ifBlank { memo.memo.content },
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        GalleryReelMemoMeta(
            memo = memo,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
        )
        Text(
            text = stringResource(R.string.gallery_reel_overlay_summary_more),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun GalleryReelExpandedMemo(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    onShowMoreMenu: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GalleryReelMemoMeta(
                memo = memo,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShowMoreMenu) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(com.lomo.ui.R.string.cd_more_options),
                    tint = Color.White,
                )
            }
        }
        val colorScheme = MaterialTheme.colorScheme
        MaterialTheme(
            colorScheme =
                colorScheme.copy(
                    primary = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color.White.copy(alpha = 0.74f),
                    surface = Color.Transparent,
                    surfaceVariant = Color.White.copy(alpha = 0.14f),
                    outline = Color.White.copy(alpha = 0.42f),
                    outlineVariant = Color.White.copy(alpha = 0.24f),
                ),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
            MarkdownRenderer(
                content = memo.processedContent,
                precomputedRenderPlan = memo.precomputedRenderPlan,
                knownTagsToStrip = memo.tags,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun GalleryReelMemoMeta(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = remember(memo.memo.timestamp, dateFormat, timeFormat) {
                memo.memo.timestamp.formatAsDateTime(dateFormat, timeFormat)
            },
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(visible = memo.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
            ) {
                memo.tags.forEach { tag ->
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "#$tag",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}
