package com.lomo.app.feature.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

internal enum class MainForegroundAutoInputPolicy {
    Ignore,
    WaitForReady,
    Suppress,
    RefocusEditor,
    OpenDraftEditor,
}

internal fun resolveMainForegroundAutoInputDecision(
    foregroundEntryId: Long,
    handledForegroundEntryId: Long,
    enabled: Boolean,
    isReady: Boolean,
    explicitEntryPending: Boolean,
    editorVisible: Boolean,
    isRecording: Boolean,
    hasPendingNewMemoCreation: Boolean,
): MainForegroundAutoInputPolicy =
    when {
        foregroundEntryId <= 0L || foregroundEntryId == handledForegroundEntryId ->
            MainForegroundAutoInputPolicy.Ignore

        !enabled || explicitEntryPending || isRecording || hasPendingNewMemoCreation ->
            MainForegroundAutoInputPolicy.Suppress

        !isReady ->
            MainForegroundAutoInputPolicy.WaitForReady

        editorVisible ->
            MainForegroundAutoInputPolicy.RefocusEditor

        else ->
            MainForegroundAutoInputPolicy.OpenDraftEditor
    }

@Composable
internal fun MainForegroundAutoInputEffect(
    foregroundEntryId: Long,
    enabled: Boolean,
    uiState: MainViewModel.MainScreenState,
    explicitEntryPending: Boolean,
    editorVisible: Boolean,
    isRecording: Boolean,
    hasPendingNewMemoCreation: Boolean,
    draftText: String,
    onOpenDraftEditor: (String) -> Unit,
    onRefocusEditor: () -> Unit,
) {
    var handledForegroundEntryId by remember { mutableLongStateOf(0L) }
    val latestDraftText by rememberUpdatedState(draftText)
    val latestOpenDraftEditor by rememberUpdatedState(onOpenDraftEditor)
    val latestRefocusEditor by rememberUpdatedState(onRefocusEditor)

    LaunchedEffect(
        foregroundEntryId,
        enabled,
        uiState,
        explicitEntryPending,
        editorVisible,
        isRecording,
        hasPendingNewMemoCreation,
    ) {
        when (
            resolveMainForegroundAutoInputDecision(
                foregroundEntryId = foregroundEntryId,
                handledForegroundEntryId = handledForegroundEntryId,
                enabled = enabled,
                isReady = uiState is MainViewModel.MainScreenState.Ready,
                explicitEntryPending = explicitEntryPending,
                editorVisible = editorVisible,
                isRecording = isRecording,
                hasPendingNewMemoCreation = hasPendingNewMemoCreation,
            )
        ) {
            MainForegroundAutoInputPolicy.Ignore,
            MainForegroundAutoInputPolicy.WaitForReady,
            -> Unit

            MainForegroundAutoInputPolicy.Suppress -> {
                handledForegroundEntryId = foregroundEntryId
            }

            MainForegroundAutoInputPolicy.RefocusEditor -> {
                latestRefocusEditor()
                handledForegroundEntryId = foregroundEntryId
            }

            MainForegroundAutoInputPolicy.OpenDraftEditor -> {
                latestOpenDraftEditor(latestDraftText)
                handledForegroundEntryId = foregroundEntryId
            }
        }
    }
}
