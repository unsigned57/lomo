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

/** One foreground-entry decision input: the entry identity plus the current session gates. */
internal data class MainForegroundAutoInputFacts(
    val foregroundEntryId: Long,
    val handledForegroundEntryId: Long,
    val enabled: Boolean,
    val isReady: Boolean,
    val explicitEntryPending: Boolean,
    val editorVisible: Boolean,
    val isRecording: Boolean,
    val hasPendingNewMemoCreation: Boolean,
)

internal fun resolveMainForegroundAutoInputDecision(
    facts: MainForegroundAutoInputFacts,
): MainForegroundAutoInputPolicy =
    when {
        facts.foregroundEntryId <= 0L ||
            facts.foregroundEntryId == facts.handledForegroundEntryId ->
            MainForegroundAutoInputPolicy.Ignore

        !facts.enabled ||
            facts.explicitEntryPending ||
            facts.isRecording ||
            facts.hasPendingNewMemoCreation ->
            MainForegroundAutoInputPolicy.Suppress

        !facts.isReady ->
            MainForegroundAutoInputPolicy.WaitForReady

        facts.editorVisible ->
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
                MainForegroundAutoInputFacts(
                    foregroundEntryId = foregroundEntryId,
                    handledForegroundEntryId = handledForegroundEntryId,
                    enabled = enabled,
                    isReady = uiState is MainViewModel.MainScreenState.Ready,
                    explicitEntryPending = explicitEntryPending,
                    editorVisible = editorVisible,
                    isRecording = isRecording,
                    hasPendingNewMemoCreation = hasPendingNewMemoCreation,
                ),
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
