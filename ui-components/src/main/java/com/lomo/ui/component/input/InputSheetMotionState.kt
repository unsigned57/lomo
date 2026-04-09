package com.lomo.ui.component.input

internal enum class InputSheetMotionStage {
    Compact,
    Expanding,
    Expanded,
    Collapsing,
}

internal enum class InputSheetSurfaceForm {
    CompactCard,
    ExpandedFullscreen,
}

internal fun resolveRequestedInputSheetMotionStage(
    targetExpanded: Boolean,
    currentStage: InputSheetMotionStage,
): InputSheetMotionStage =
    if (targetExpanded) {
        when (currentStage) {
            InputSheetMotionStage.Compact,
            InputSheetMotionStage.Collapsing,
            -> InputSheetMotionStage.Expanding

            InputSheetMotionStage.Expanding,
            InputSheetMotionStage.Expanded,
            -> currentStage
        }
    } else {
        when (currentStage) {
            InputSheetMotionStage.Expanded,
            InputSheetMotionStage.Expanding,
            -> InputSheetMotionStage.Collapsing

            InputSheetMotionStage.Collapsing,
            InputSheetMotionStage.Compact,
            -> currentStage
        }
    }

internal fun resolveSettledInputSheetMotionStage(targetExpanded: Boolean): InputSheetMotionStage =
    if (targetExpanded) {
        InputSheetMotionStage.Expanded
    } else {
        InputSheetMotionStage.Compact
    }

internal fun resolveInputSheetSurfaceForm(stage: InputSheetMotionStage): InputSheetSurfaceForm =
    if (stage == InputSheetMotionStage.Compact) {
        InputSheetSurfaceForm.CompactCard
    } else {
        InputSheetSurfaceForm.ExpandedFullscreen
    }

internal fun InputSheetMotionStage.usesExpandedSurfaceForm(): Boolean =
    resolveInputSheetSurfaceForm(this) == InputSheetSurfaceForm.ExpandedFullscreen

internal fun InputSheetMotionStage.showsCompactChrome(): Boolean = this == InputSheetMotionStage.Compact


