package com.lomo.app.feature.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.presentation.markdown.MemoMarkdownMediaAdapter
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.MotionTokens
import com.lomo.ui.util.formatAsDateTime

private const val GALLERY_REEL_PANEL_CONTENT_ALPHA = 0.78f
private const val GALLERY_REEL_PANEL_COMPACT_ALPHA = 0.72f
private const val GALLERY_REEL_PANEL_HEIGHT_ANIMATION_MILLIS = 260
private const val GALLERY_REEL_PANEL_CONTENT_SWITCH_MILLIS = 120
private val GALLERY_REEL_PANEL_TONAL_ELEVATION = 6.dp
private val GALLERY_REEL_PANEL_MAX_HEIGHT = 360.dp
private val GALLERY_REEL_PANEL_CONTENT_TOP_PADDING = 24.dp
private val GALLERY_REEL_PANEL_CONTENT_BOTTOM_PADDING = AppSpacing.MediumSmall
private val GALLERY_REEL_PANEL_CONTENT_TOP_CORNER = 32.dp
private val GALLERY_REEL_PANEL_COMPACT_BOTTOM_INSET = 32.dp
private val GALLERY_REEL_PANEL_COMPACT_HORIZONTAL_INSET = 16.dp
private val GALLERY_REEL_PANEL_COMPACT_PILL_PADDING_HORIZONTAL = 16.dp
private val GALLERY_REEL_PANEL_COMPACT_PILL_PADDING_VERTICAL = 8.dp
private val GALLERY_REEL_PANEL_COMPACT_ROW_SPACING = 8.dp
private val GALLERY_REEL_MARKDOWN_IMAGE_REGEX = Regex("!\\[.*?\\]\\(.*?\\)")

private enum class GalleryReelPanelMode {
    Content,
    Compact,
}

@Composable
fun GalleryReelMemoOverlay(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    onTodoClick: (com.lomo.domain.model.Memo, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showMemoDetails: Boolean = true,
    imageIndicator: GalleryReelImageIndicatorState? = null,
) {
    val panelMode = rememberGalleryReelPanelMode(memo, showMemoDetails)
    val panelTransition = updateTransition(targetState = panelMode, label = "GalleryReelPanelMode")

    Box(modifier = modifier.fillMaxSize()) {
        GalleryReelPanelSurface(
            memo = memo,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            panelTransition = panelTransition,
            showMemoDetails = showMemoDetails,
            onTodoClick = onTodoClick,
            modifier = Modifier.align(Alignment.BottomCenter),
            imageIndicator = imageIndicator,
        )
    }
}

@Composable
private fun rememberGalleryReelPanelMode(
    memo: MemoUiModel,
    showMemoDetails: Boolean,
): GalleryReelPanelMode {
    val rawContent = memo.processedContent.ifBlank { memo.memo.content }
    return remember(rawContent, memo.tags, showMemoDetails) {
        if (showMemoDetails && galleryReelHasDisplayContent(rawContent, memo.tags)) {
            GalleryReelPanelMode.Content
        } else {
            GalleryReelPanelMode.Compact
        }
    }
}

private fun galleryReelHasDisplayContent(
    rawContent: String,
    tags: List<String>,
): Boolean = galleryReelTextContent(rawContent).isNotBlank() || tags.isNotEmpty()

@Composable
private fun GalleryReelPanelSurface(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    panelTransition: Transition<GalleryReelPanelMode>,
    showMemoDetails: Boolean,
    onTodoClick: (com.lomo.domain.model.Memo, Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    imageIndicator: GalleryReelImageIndicatorState? = null,
) {
    val targetMode = panelTransition.targetState
    val containerColor =
        panelTransition.animateColor(label = "GalleryReelPanelContainerColor") { mode ->
            val alpha =
                if (mode == GalleryReelPanelMode.Content) {
                    GALLERY_REEL_PANEL_CONTENT_ALPHA
                } else {
                    GALLERY_REEL_PANEL_COMPACT_ALPHA
                }
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha)
        }
    val topCorner =
        panelTransition.animateDp(label = "GalleryReelPanelTopCorner") { mode ->
            if (mode == GalleryReelPanelMode.Content) {
                GALLERY_REEL_PANEL_CONTENT_TOP_CORNER
            } else {
                GALLERY_REEL_PANEL_CONTENT_TOP_CORNER
            }
        }

    Surface(
        color = containerColor.value,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape =
            if (targetMode == GalleryReelPanelMode.Content) {
                RoundedCornerShape(
                    topStart = topCorner.value,
                    topEnd = topCorner.value,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp,
                )
            } else {
                AppShapes.Full
            },
        tonalElevation = GALLERY_REEL_PANEL_TONAL_ELEVATION,
        modifier =
            modifier
                .then(
                    if (targetMode == GalleryReelPanelMode.Content) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                            .padding(
                                horizontal = GALLERY_REEL_PANEL_COMPACT_HORIZONTAL_INSET,
                                vertical = 0.dp,
                            )
                            .padding(bottom = GALLERY_REEL_PANEL_COMPACT_BOTTOM_INSET)
                            .navigationBarsPadding()
                    },
                )
                .animateContentSize(
                    animationSpec =
                        tween(
                            durationMillis = GALLERY_REEL_PANEL_HEIGHT_ANIMATION_MILLIS,
                            easing = MotionTokens.EasingStandard,
                        ),
                    alignment = Alignment.BottomCenter,
                ),
    ) {
        AnimatedContent(
            targetState = targetMode,
            transitionSpec = {
                fadeIn(
                    animationSpec =
                        tween(
                            durationMillis = GALLERY_REEL_PANEL_CONTENT_SWITCH_MILLIS,
                            easing = MotionTokens.EasingStandard,
                        ),
                ) togetherWith
                    fadeOut(
                        animationSpec =
                            tween(
                                durationMillis = GALLERY_REEL_PANEL_CONTENT_SWITCH_MILLIS,
                                easing = MotionTokens.EasingStandard,
                            ),
                    )
            },
            label = "GalleryReelPanelContent",
        ) { mode ->
            when (mode) {
                GalleryReelPanelMode.Content ->
                    GalleryReelTextPanelContent(
                        memo = memo,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        imageIndicator = imageIndicator,
                        onTodoClick = onTodoClick,
                    )
                GalleryReelPanelMode.Compact ->
                    GalleryReelCompactPillContent(
                        memo = memo,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        showMemoDetails = showMemoDetails,
                        imageIndicator = imageIndicator,
                    )
            }
        }
    }
}

@Composable
private fun GalleryReelTextPanelContent(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    imageIndicator: GalleryReelImageIndicatorState?,
    onTodoClick: (com.lomo.domain.model.Memo, Int, Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        verticalArrangement = Arrangement.spacedBy(AppSpacing.MediumSmall),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = GALLERY_REEL_PANEL_MAX_HEIGHT)
                .navigationBarsPadding()
                .padding(
                    start = AppSpacing.Medium,
                    top = GALLERY_REEL_PANEL_CONTENT_TOP_PADDING,
                    end = AppSpacing.Medium,
                    bottom = GALLERY_REEL_PANEL_CONTENT_BOTTOM_PADDING,
                )
                .verticalScroll(scrollState),
    ) {
        imageIndicator?.let { state ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                GalleryReelImageDots(state = state)
            }
        }
        val content = rememberGalleryReelTextContent(memo)
        if (content.isNotBlank()) {
            val stableTodoClick = remember(memo.memo, onTodoClick) {
                { lineIndex: Int, checked: Boolean ->
                    onTodoClick(memo.memo, lineIndex, checked)
                }
            }
            MarkdownRenderer(
                content = content,
                knownTagsToStrip = memo.tags,
                onTodoClick = stableTodoClick,
                modifier = Modifier.fillMaxWidth(),
                mediaPresentationResolver = MemoMarkdownMediaAdapter.resolver,
                mediaContent = MemoMarkdownMediaAdapter.content,
                hideImages = true,
            )
        }
        GalleryReelMemoMeta(
            memo = memo,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
        )
    }
}

@Composable
private fun GalleryReelCompactPillContent(
    memo: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    showMemoDetails: Boolean,
    imageIndicator: GalleryReelImageIndicatorState?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GALLERY_REEL_PANEL_COMPACT_ROW_SPACING),
        modifier =
            Modifier.padding(
                horizontal = GALLERY_REEL_PANEL_COMPACT_PILL_PADDING_HORIZONTAL,
                vertical = GALLERY_REEL_PANEL_COMPACT_PILL_PADDING_VERTICAL,
            ),
    ) {
        if (showMemoDetails) {
            val dateText = remember(memo.memo.timestamp, dateFormat, timeFormat) {
                memo.memo.timestamp.formatAsDateTime(dateFormat, timeFormat)
            }
            Text(
                text = dateText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        imageIndicator?.let { state ->
            if (showMemoDetails) {
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            GalleryReelImageDotsInline(state = state)
        }
    }
}

@Composable
private fun rememberGalleryReelTextContent(memo: MemoUiModel): String {
    val rawContent = memo.processedContent.ifBlank { memo.memo.content }
    return remember(rawContent) {
        rawContent
    }
}

private fun galleryReelTextContent(rawContent: String): String =
    rawContent
        .replace(GALLERY_REEL_MARKDOWN_IMAGE_REGEX, "")
        .trim()

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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (memo.tags.isNotEmpty()) {
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
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        label = {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelLarge,
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
