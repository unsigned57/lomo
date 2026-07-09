package com.lomo.ui.component.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.delay

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
internal fun rememberInputSheetPresentationState(
    targetExpanded: Boolean,
    targetDisplayMode: InputEditorDisplayMode,
): InputSheetPresentationState {
    var presentationState by remember {
        mutableStateOf(resolveSettledInputSheetPresentationState(targetExpanded, targetDisplayMode))
    }

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
            delay(MotionTokens.DurationMedium2.toLong())
            presentationState = resolveSettledInputSheetPresentationState(targetExpanded, targetDisplayMode)
        }
    }

    return presentationState
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

