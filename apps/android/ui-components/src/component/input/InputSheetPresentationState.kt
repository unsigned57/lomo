package com.lomo.ui.component.input

import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

internal enum class InputSheetPresentationState {
    CompactEdit,
    ExpandingToEdit,
    ExpandedEdit,
    SwitchingToPreview,
    ExpandedPreview,
    SwitchingToEdit,
    CollapsingFromEdit,
    CollapsingFromPreview,
}

@Composable
internal fun rememberInputSheetPresentationTransition(
    targetExpanded: Boolean,
    targetDisplayMode: InputEditorDisplayMode,
): Transition<InputSheetPresentationState> {
    var presentationState by remember {
        mutableStateOf(resolveSettledInputSheetPresentationState(targetExpanded, targetDisplayMode))
    }

    val transition = updateTransition(presentationState, label = "InputSheetPresentation")
    LaunchedEffect(targetExpanded, targetDisplayMode, presentationState) {
        val requestedState =
            resolveRequestedInputSheetPresentationState(
                targetExpanded = targetExpanded,
                targetDisplayMode = targetDisplayMode,
                currentState = presentationState,
            )
        if (requestedState != presentationState) {
            presentationState = requestedState
            return@LaunchedEffect
        }

        val settledState = resolveSettledInputSheetPresentationState(targetExpanded, targetDisplayMode)
        if (presentationState != settledState) {
            snapshotFlow { transition.currentState == presentationState && !transition.isRunning }
                .first { it }
            presentationState = resolveSettledInputSheetPresentationState(targetExpanded, targetDisplayMode)
        }
    }

    return transition
}

internal fun resolveRequestedInputSheetPresentationState(
    targetExpanded: Boolean,
    targetDisplayMode: InputEditorDisplayMode,
    currentState: InputSheetPresentationState,
): InputSheetPresentationState {
    if (!targetExpanded) {
        return when (currentState) {
            InputSheetPresentationState.CompactEdit -> InputSheetPresentationState.CompactEdit
            InputSheetPresentationState.CollapsingFromEdit,
            InputSheetPresentationState.CollapsingFromPreview,
            -> currentState

            InputSheetPresentationState.SwitchingToPreview,
            InputSheetPresentationState.ExpandedPreview,
            InputSheetPresentationState.SwitchingToEdit,
            -> InputSheetPresentationState.CollapsingFromPreview

            InputSheetPresentationState.ExpandingToEdit,
            InputSheetPresentationState.ExpandedEdit,
            -> InputSheetPresentationState.CollapsingFromEdit
        }
    }

    return when (targetDisplayMode) {
        InputEditorDisplayMode.Edit ->
            when (currentState) {
                InputSheetPresentationState.CompactEdit,
                InputSheetPresentationState.CollapsingFromEdit,
                InputSheetPresentationState.CollapsingFromPreview,
                -> InputSheetPresentationState.ExpandingToEdit

                InputSheetPresentationState.ExpandingToEdit,
                InputSheetPresentationState.ExpandedEdit,
                -> currentState

                InputSheetPresentationState.SwitchingToPreview,
                InputSheetPresentationState.ExpandedPreview,
                InputSheetPresentationState.SwitchingToEdit,
                -> InputSheetPresentationState.SwitchingToEdit
            }

        InputEditorDisplayMode.Preview ->
            when (currentState) {
                InputSheetPresentationState.CompactEdit,
                InputSheetPresentationState.ExpandingToEdit,
                InputSheetPresentationState.CollapsingFromEdit,
                InputSheetPresentationState.CollapsingFromPreview,
                -> InputSheetPresentationState.ExpandingToEdit

                InputSheetPresentationState.ExpandedEdit,
                InputSheetPresentationState.SwitchingToEdit,
                -> InputSheetPresentationState.SwitchingToPreview

                InputSheetPresentationState.SwitchingToPreview,
                InputSheetPresentationState.ExpandedPreview,
                -> currentState
            }
    }
}

internal fun resolveSettledInputSheetPresentationState(
    targetExpanded: Boolean,
    targetDisplayMode: InputEditorDisplayMode,
): InputSheetPresentationState =
    if (!targetExpanded) {
        InputSheetPresentationState.CompactEdit
    } else {
        when (targetDisplayMode) {
            InputEditorDisplayMode.Edit -> InputSheetPresentationState.ExpandedEdit
            InputEditorDisplayMode.Preview -> InputSheetPresentationState.ExpandedPreview
        }
    }


/** Child animations register with the editor's single presentation transition. */
internal val LocalInputSheetPresentationTransition =
    staticCompositionLocalOf<Transition<InputSheetPresentationState>> {
        error("Input sheet motion must be hosted by InputSheet")
    }
