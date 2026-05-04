package com.lomo.ui.component.card

import com.lomo.ui.theme.MotionTokens

internal enum class MemoCardBodyTransitionMode {
    Snap,
    StateContentTransform,
}

internal enum class MemoCardBodyVisualState {
    Expanded,
    CollapsedSummary,
    CollapsedMarkdownPreview,
}

internal enum class MemoCardBodyContainerSizeAnimation {
    Enabled,
    Disabled,
}

internal data class MemoCardBodyMotionSpec(
    val sizeEnterDurationMillis: Int,
    val sizeExitDurationMillis: Int,
    val fadeInDurationMillis: Int,
    val fadeOutDurationMillis: Int,
    val fadeInDelayMillis: Int,
)

internal data class MemoCardBodyContentSwitchSpec(
    val targetContentEnterDurationMillis: Int,
    val targetContentEnterDelayMillis: Int,
    val initialContentExitDurationMillis: Int,
    val initialContentExitDelayMillis: Int,
)

internal fun resolveMemoCardBodyTransitionMode(shouldShowExpand: Boolean): MemoCardBodyTransitionMode =
    if (shouldShowExpand) {
        MemoCardBodyTransitionMode.StateContentTransform
    } else {
        MemoCardBodyTransitionMode.Snap
    }

internal fun resolveMemoCardBodyContainerSizeAnimation(
    bodyTransitionMode: MemoCardBodyTransitionMode,
): MemoCardBodyContainerSizeAnimation =
    when (bodyTransitionMode) {
        MemoCardBodyTransitionMode.Snap -> MemoCardBodyContainerSizeAnimation.Enabled
        MemoCardBodyTransitionMode.StateContentTransform -> MemoCardBodyContainerSizeAnimation.Disabled
    }

internal fun resolveMemoCardBodyCollapsedTargetPreviewMode(
    bodyTransitionMode: MemoCardBodyTransitionMode,
    currentPreviewMode: MemoCardCollapsedPreviewMode,
    hasProcessedContent: Boolean,
    collapsedSummary: String,
): MemoCardCollapsedPreviewMode =
    when (bodyTransitionMode) {
        MemoCardBodyTransitionMode.Snap -> currentPreviewMode
        MemoCardBodyTransitionMode.StateContentTransform ->
            resolveMemoCardCollapsedPreviewMode(
                isCollapsedPreview = true,
                hasProcessedContent = hasProcessedContent,
                collapsedSummary = collapsedSummary,
            )
    }

internal fun resolveMemoCardBodyVisualState(
    isExpanded: Boolean,
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
): MemoCardBodyVisualState =
    when {
        isExpanded -> MemoCardBodyVisualState.Expanded
        collapsedPreviewMode == MemoCardCollapsedPreviewMode.Summary -> MemoCardBodyVisualState.CollapsedSummary
        collapsedPreviewMode == MemoCardCollapsedPreviewMode.MarkdownPreview ->
            MemoCardBodyVisualState.CollapsedMarkdownPreview
        else -> MemoCardBodyVisualState.Expanded
    }

internal fun resolveMemoCardBodyMotionSpec(
    bodyTransitionMode: MemoCardBodyTransitionMode,
): MemoCardBodyMotionSpec =
    when (bodyTransitionMode) {
        MemoCardBodyTransitionMode.Snap ->
            MemoCardBodyMotionSpec(
                sizeEnterDurationMillis = 0,
                sizeExitDurationMillis = 0,
                fadeInDurationMillis = 0,
                fadeOutDurationMillis = 0,
                fadeInDelayMillis = 0,
            )

        MemoCardBodyTransitionMode.StateContentTransform ->
            MemoCardBodyMotionSpec(
                sizeEnterDurationMillis = MotionTokens.DurationLong2,
                sizeExitDurationMillis = MotionTokens.DurationLong2,
                fadeInDurationMillis = 0,
                fadeOutDurationMillis = 0,
                fadeInDelayMillis = 0,
            )
    }

internal fun resolveMemoCardBodyContentSwitchSpec(
    motionSpec: MemoCardBodyMotionSpec,
    isExpanding: Boolean,
): MemoCardBodyContentSwitchSpec =
    if (isExpanding) {
        MemoCardBodyContentSwitchSpec(
            targetContentEnterDurationMillis = motionSpec.fadeInDurationMillis,
            targetContentEnterDelayMillis = motionSpec.fadeInDelayMillis,
            initialContentExitDurationMillis = motionSpec.fadeOutDurationMillis,
            initialContentExitDelayMillis = 0,
        )
    } else {
        MemoCardBodyContentSwitchSpec(
            targetContentEnterDurationMillis = motionSpec.fadeInDurationMillis,
            targetContentEnterDelayMillis = motionSpec.sizeExitDurationMillis,
            initialContentExitDurationMillis = motionSpec.fadeOutDurationMillis,
            initialContentExitDelayMillis = motionSpec.sizeExitDurationMillis,
        )
    }
