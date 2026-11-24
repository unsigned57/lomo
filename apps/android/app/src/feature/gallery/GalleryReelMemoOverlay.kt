package com.lomo.app.feature.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.presentation.markdown.MemoMarkdownMediaAdapter
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

private const val GALLERY_REEL_PANEL_GLASS_TINT_ALPHA = 0.4f
private val GALLERY_REEL_PANEL_MAX_HEIGHT = 360.dp
private val GALLERY_REEL_PANEL_CONTENT_TOP_PADDING = 24.dp
private val GALLERY_REEL_PANEL_CONTENT_BOTTOM_PADDING = AppSpacing.MediumSmall
private val GALLERY_REEL_PANEL_CONTENT_TOP_CORNER = 32.dp
private val GALLERY_REEL_PANEL_COMPACT_BOTTOM_INSET = 32.dp
private val GALLERY_REEL_PANEL_COMPACT_HORIZONTAL_INSET = 16.dp
private val GALLERY_REEL_PANEL_COMPACT_PILL_PADDING_HORIZONTAL = 16.dp
private val GALLERY_REEL_PANEL_COMPACT_PILL_PADDING_VERTICAL = 8.dp
private val GALLERY_REEL_PANEL_COMPACT_ROW_SPACING = 8.dp

private enum class GalleryReelPanelMode {
    Content,
    Compact,
}

@Composable
fun GalleryReelMemoOverlay(
    hazeState: HazeState,
    onTagClick: (String) -> Unit,
    memo: MemoUiModel?,
    onTodoClick: (com.lomo.domain.model.Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    modifier: Modifier = Modifier,
    showMemoDetails: Boolean = true,
    imageIndicator: GalleryReelImageIndicatorState? = null,
) {
    val panelMode = rememberGalleryReelPanelMode(memo, showMemoDetails)
    val panelTransition = updateTransition(targetState = panelMode, label = "GalleryReelPanelMode")

    Box(modifier = modifier.fillMaxWidth()) {
        GalleryReelPanelSurface(
            hazeState = hazeState,
            onTagClick = onTagClick,
            memo = memo,
            panelTransition = panelTransition,
            showMemoDetails = showMemoDetails,
            onTodoClick = onTodoClick,
            modifier = Modifier.align(Alignment.BottomCenter),
            imageIndicator = imageIndicator,
        )
    }
}

@Composable
private fun rememberGalleryReelPanelMode(memo: MemoUiModel?, showMemoDetails: Boolean): GalleryReelPanelMode {
    val plainText = memo?.let { it.renderDocument.plainText }.orEmpty()
    val tags = memo?.tags.orEmpty()
    return remember(plainText, tags, showMemoDetails) {
        if (showMemoDetails && galleryReelHasDisplayContent(plainText, tags)) {
            GalleryReelPanelMode.Content
        } else {
            GalleryReelPanelMode.Compact
        }
    }
}

private fun galleryReelHasDisplayContent(plainText: String, tags: List<String>): Boolean =
    plainText.isNotBlank() || tags.isNotEmpty()

@Composable
private fun GalleryReelPanelSurface(
    hazeState: HazeState,
    onTagClick: (String) -> Unit,
    memo: MemoUiModel?,
    panelTransition: Transition<GalleryReelPanelMode>,
    showMemoDetails: Boolean,
    onTodoClick: (com.lomo.domain.model.Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    modifier: Modifier = Modifier,
    imageIndicator: GalleryReelImageIndicatorState? = null,
) {
    val motionScheme = LocalLomoMotion.current.layout
    val targetMode = panelTransition.targetState
    val panelShape =
        if (targetMode == GalleryReelPanelMode.Content) {
            RoundedCornerShape(
                topStart = GALLERY_REEL_PANEL_CONTENT_TOP_CORNER,
                topEnd = GALLERY_REEL_PANEL_CONTENT_TOP_CORNER,
            )
        } else {
            AppShapes.Full
        }
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val glassStyle = remember(panelShape, surfaceColor) {
        GlassStyle.regular.then {
            shape(panelShape)
            backgroundColor(surfaceColor)
            tint(surfaceColor.copy(alpha = GALLERY_REEL_PANEL_GLASS_TINT_ALPHA))
        }
    }

    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = panelShape,
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
                motionScheme.defaultSpatialSpec(),
                alignment = Alignment.BottomCenter,
            )
            .hazeGlass(input = HazeInput.Sources(hazeState), style = glassStyle),
    ) {
        AnimatedContent(
            targetState = targetMode,
            transitionSpec = {
                fadeIn(
                    animationSpec =
                    motionScheme.defaultEffectsSpec(),
                ) togetherWith
                    fadeOut(
                        animationSpec =
                        motionScheme.defaultEffectsSpec(),
                    )
            },
            label = "GalleryReelPanelContent",
        ) { mode ->
            when (mode) {
                GalleryReelPanelMode.Content ->
                    memo?.let { resolvedMemo ->
                        GalleryReelTextPanelContent(
                            onTagClick = onTagClick,
                            memo = resolvedMemo,
                            imageIndicator = imageIndicator,
                            onTodoClick = onTodoClick,
                        )
                    }

                GalleryReelPanelMode.Compact ->
                    GalleryReelCompactPillContent(
                        memo = memo,
                        showMemoDetails = showMemoDetails && memo != null,
                        imageIndicator = imageIndicator,
                    )
            }
        }
    }
}

@Composable
private fun GalleryReelTextPanelContent(
    onTagClick: (String) -> Unit,
    memo: MemoUiModel,
    imageIndicator: GalleryReelImageIndicatorState?,
    onTodoClick: (com.lomo.domain.model.Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
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
        if (memo.renderDocument.plainText.isNotBlank()) {
            val stableTodoClick = remember(memo.memo, onTodoClick) {
                if (memo.memo.isDeleted) {
                    null
                } else {
                    { actionSpan: com.lomo.domain.model.markdown.MarkdownSourceSpan ->
                        onTodoClick(memo.memo, actionSpan)
                    }
                }
            }
            MarkdownRenderer(
                document = memo.renderDocument,
                presentationPlan = memo.presentationPlan,
                onTodoClick = stableTodoClick,
                modifier = Modifier.fillMaxWidth(),
                mediaPresentationResolver = MemoMarkdownMediaAdapter.resolver,
                mediaContent = MemoMarkdownMediaAdapter.content,
                hideImages = true,
            )
        }
        GalleryReelMemoMeta(
            onTagClick = onTagClick,
            memo = memo,
        )
    }
}

@Composable
private fun GalleryReelCompactPillContent(
    memo: MemoUiModel?,
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
        val showMemoDate = showMemoDetails && memo != null
        if (showMemoDate) {
            val dateText = remember(memo.memo) {
                memo.memo.displayTimestamp()
            }
            Text(
                text = dateText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        imageIndicator?.let { state ->
            if (showMemoDate) {
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
private fun GalleryReelMemoMeta(onTagClick: (String) -> Unit, memo: MemoUiModel, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = remember(memo.memo) {
                memo.memo.displayTimestamp()
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
                        onClick = { onTagClick(tag) },
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
