package com.lomo.ui.component.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.MotionTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun MemoCardBodyContent(
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    collapsedSummary: String,
    allowFreeTextCopy: Boolean,
    processedContent: String,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    tags: ImmutableList<String>,
    isCollapsedPreview: Boolean,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    bodyTransitionMode: MemoCardBodyTransitionMode,
) {
    val bodyContent: @Composable () -> Unit = {
        if (collapsedPreviewMode != MemoCardCollapsedPreviewMode.Summary) {
            MemoCardMarkdownContent(
                processedContent = processedContent,
                precomputedRenderPlan = precomputedRenderPlan,
                tags = tags,
                isCollapsedPreview = isCollapsedPreview,
                allowFreeTextCopy = allowFreeTextCopy,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
            )
        } else {
            when (bodyTransitionMode) {
                MemoCardBodyTransitionMode.Snap -> {
                    MemoCardBodyBranch(
                        useCollapsedSummary = true,
                        collapsedSummary = collapsedSummary,
                        allowFreeTextCopy = allowFreeTextCopy,
                        processedContent = processedContent,
                        precomputedRenderPlan = precomputedRenderPlan,
                        tags = tags,
                        isCollapsedPreview = isCollapsedPreview,
                        onTodoClick = onTodoClick,
                        todoOverrides = todoOverrides,
                        onImageClick = onImageClick,
                    )
                }

                MemoCardBodyTransitionMode.VerticalVisibility -> {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        AnimatedVisibility(
                            visible = true,
                            enter = memoCardBodyEnterTransition(),
                            exit = memoCardBodyExitTransition(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MemoCardCollapsedSummary(
                                collapsedSummary = collapsedSummary,
                                allowFreeTextCopy = allowFreeTextCopy,
                            )
                        }
                        AnimatedVisibility(
                            visible = false,
                            enter = memoCardBodyEnterTransition(),
                            exit = memoCardBodyExitTransition(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MemoCardMarkdownContent(
                                processedContent = processedContent,
                                precomputedRenderPlan = precomputedRenderPlan,
                                tags = tags,
                                isCollapsedPreview = isCollapsedPreview,
                                allowFreeTextCopy = allowFreeTextCopy,
                                onTodoClick = onTodoClick,
                                todoOverrides = todoOverrides,
                                onImageClick = onImageClick,
                            )
                        }
                    }
                }
            }
        }
    }

    bodyContent()
}

@Composable
private fun MemoCardBodyBranch(
    useCollapsedSummary: Boolean,
    collapsedSummary: String,
    allowFreeTextCopy: Boolean,
    processedContent: String,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    tags: ImmutableList<String>,
    isCollapsedPreview: Boolean,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
) {
    if (useCollapsedSummary) {
        MemoCardCollapsedSummary(
            collapsedSummary = collapsedSummary,
            allowFreeTextCopy = allowFreeTextCopy,
        )
    } else {
        MemoCardMarkdownContent(
            processedContent = processedContent,
            precomputedRenderPlan = precomputedRenderPlan,
            tags = tags,
            isCollapsedPreview = isCollapsedPreview,
            allowFreeTextCopy = allowFreeTextCopy,
            onTodoClick = onTodoClick,
            todoOverrides = todoOverrides,
            onImageClick = onImageClick,
        )
    }
}

@Composable
private fun MemoCardMarkdownContent(
    processedContent: String,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    tags: ImmutableList<String>,
    isCollapsedPreview: Boolean,
    allowFreeTextCopy: Boolean,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
) {
    MarkdownRenderer(
        content = processedContent,
        precomputedRenderPlan = precomputedRenderPlan,
        knownTagsToStrip = if (precomputedRenderPlan == null) tags else persistentListOf(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        maxVisibleBlocks = if (isCollapsedPreview) COLLAPSED_MAX_VISIBLE_BLOCKS else Int.MAX_VALUE,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        enableTextSelection = allowFreeTextCopy,
    )
}

private fun memoCardBodyEnterTransition() =
    fadeIn(
        animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
    ) +
        expandVertically(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationMedium2,
                    easing = MotionTokens.EasingEmphasizedDecelerate,
                ),
            expandFrom = Alignment.Top,
        )

private fun memoCardBodyExitTransition() =
    fadeOut(
        animationSpec = tween(durationMillis = MotionTokens.DurationShort4),
    ) +
        shrinkVertically(
            animationSpec =
                tween(
                    durationMillis = MotionTokens.DurationShort4,
                    easing = MotionTokens.EasingEmphasizedAccelerate,
                ),
            shrinkTowards = Alignment.Top,
        )
