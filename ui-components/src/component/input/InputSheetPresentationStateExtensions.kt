package com.lomo.ui.component.input

internal fun InputSheetPresentationState.surfaceMotionStage(): InputSheetMotionStage =
    when (this) {
        InputSheetPresentationState.CompactEdit -> InputSheetMotionStage.Compact
        InputSheetPresentationState.ExpandingToEdit -> InputSheetMotionStage.Expanding
        InputSheetPresentationState.CollapsingFromEdit,
        InputSheetPresentationState.CollapsingFromPreview,
        -> InputSheetMotionStage.Collapsing
        InputSheetPresentationState.ExpandedEdit,
        InputSheetPresentationState.SwitchingToPreview,
        InputSheetPresentationState.ExpandedPreview,
        InputSheetPresentationState.SwitchingToEdit,
        -> InputSheetMotionStage.Expanded
    }

internal fun InputSheetPresentationState.showsEditorContent(): Boolean =
    this != InputSheetPresentationState.ExpandedPreview &&
        this != InputSheetPresentationState.CollapsingFromPreview

internal fun InputSheetPresentationState.showsPreviewLayer(): Boolean =
    when (this) {
        InputSheetPresentationState.SwitchingToPreview,
        InputSheetPresentationState.ExpandedPreview,
        InputSheetPresentationState.SwitchingToEdit,
        InputSheetPresentationState.CollapsingFromPreview,
        -> true

        else -> false
    }

internal fun InputSheetPresentationState.showsFormattingToolbar(): Boolean =
    when (this) {
        InputSheetPresentationState.CompactEdit,
        InputSheetPresentationState.ExpandingToEdit,
        InputSheetPresentationState.ExpandedEdit,
        InputSheetPresentationState.SwitchingToEdit,
        InputSheetPresentationState.CollapsingFromEdit,
        -> true

        InputSheetPresentationState.SwitchingToPreview,
        InputSheetPresentationState.ExpandedPreview,
        InputSheetPresentationState.CollapsingFromPreview,
        -> false
    }

internal fun InputSheetPresentationState.showsDisplayModeToggle(): Boolean =
    this != InputSheetPresentationState.CompactEdit

internal fun InputSheetPresentationState.prefersEditorFocus(): Boolean =
    when (this) {
        InputSheetPresentationState.CompactEdit,
        InputSheetPresentationState.ExpandingToEdit,
        InputSheetPresentationState.ExpandedEdit,
        InputSheetPresentationState.CollapsingFromEdit,
        -> true

        InputSheetPresentationState.SwitchingToPreview,
        InputSheetPresentationState.ExpandedPreview,
        InputSheetPresentationState.SwitchingToEdit,
        InputSheetPresentationState.CollapsingFromPreview,
        -> false
    }

internal fun InputSheetPresentationState.shouldReleaseEditorFocus(): Boolean =
    this == InputSheetPresentationState.ExpandedPreview

internal fun InputSheetPresentationState.effectiveDisplayMode(): InputEditorDisplayMode =
    when (this) {
        InputSheetPresentationState.SwitchingToPreview,
        InputSheetPresentationState.ExpandedPreview,
        InputSheetPresentationState.CollapsingFromPreview,
        -> InputEditorDisplayMode.Preview

        else -> InputEditorDisplayMode.Edit
    }
