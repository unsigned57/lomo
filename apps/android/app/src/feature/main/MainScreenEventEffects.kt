package com.lomo.app.feature.main

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.domain.model.Memo
import kotlinx.collections.immutable.ImmutableList

@Composable
internal fun MainScreenEventEffectsHost(
    sharedContentEvents: ImmutableList<PendingUiEvent<MainViewModel.SharedContent>>,
    appActionEvents: ImmutableList<PendingUiEvent<MainViewModel.AppAction>>,
    pendingSharedImageEvents: ImmutableList<PendingUiEvent<Uri>>,
    imageDirectory: String?,
    errorMessage: String?,
    editorErrorMessage: String?,
    recordingErrorMessage: String?,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    memoNotFoundMessage: String,
    onAppendMarkdown: (String) -> Unit,
    onAppendImageMarkdown: (String) -> Unit,
    onEnsureEditorVisible: () -> Unit,
    /** Opens the verified full-snapshot editor for this identity — never a row's carried body. */
    onOpenEditMemo: (String) -> Unit,
    onFocusMemoInList: suspend (String) -> MainScreenFocusAttempt,
    onMainListFocusConsumed: () -> Unit,
    focusRetryKey: Any?,
    onResolveMemoById: suspend (String) -> Memo?,
    onSaveImage: (Uri, (String) -> Unit, () -> Unit) -> Unit,
    onRequireImageDirectory: () -> Unit,
    onConsumeSharedContentEvent: (Long) -> Unit,
    onConsumeAppActionEvent: (Long) -> Unit,
    onConsumePendingSharedImageEvent: (Long) -> Unit,
    onClearMainError: () -> Unit,
    onClearEditorError: () -> Unit,
    onClearRecordingError: () -> Unit,
) {
    HandleSharedContentEvents(
        events = sharedContentEvents,
        onAppendText = { markdown ->
            onAppendMarkdown(markdown)
            onEnsureEditorVisible()
        },
        onConsume = onConsumeSharedContentEvent,
    )

    HandleAppActionEvents(
        events = appActionEvents,
        focusMemoInList = onFocusMemoInList,
        resolveMemoById = onResolveMemoById,
        openEdit = onOpenEditMemo,
        focusRetryKey = focusRetryKey,
        onConsume = onConsumeAppActionEvent,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
        memoNotFoundMessage = memoNotFoundMessage,
        onMainListFocusConsumed = onMainListFocusConsumed,
    )

    HandlePendingSharedImageEvents(
        imageDirectory = imageDirectory,
        events = pendingSharedImageEvents,
        onImageDirectoryMissing = onRequireImageDirectory,
        onSaveImage = onSaveImage,
        onAppendImageMarkdown = onAppendImageMarkdown,
        onEnsureEditorVisible = onEnsureEditorVisible,
        onConsume = onConsumePendingSharedImageEvent,
    )

    HandleErrorEffects(
        errorMessage = errorMessage,
        editorErrorMessage = editorErrorMessage,
        recordingErrorMessage = recordingErrorMessage,
        snackbarHostState = snackbarHostState,
        clearMainError = onClearMainError,
        clearEditorError = onClearEditorError,
        clearRecordingError = onClearRecordingError,
    )
}

@Composable
fun HandleSharedContentEvents(
    events: ImmutableList<PendingUiEvent<MainViewModel.SharedContent>>,
    onAppendText: (String) -> Unit,
    onConsume: (Long) -> Unit,
) {
    LaunchedEffect(events) {
        events.forEach { event ->
            when (val content = event.payload) {
                is MainViewModel.SharedContent.Text -> onAppendText(content.content)
            }
            onConsume(event.id)
        }
    }
}

@Composable
internal fun HandleAppActionEvents(
    events: ImmutableList<PendingUiEvent<MainViewModel.AppAction>>,
    focusMemoInList: suspend (String) -> MainScreenFocusAttempt,
    resolveMemoById: suspend (String) -> com.lomo.domain.model.Memo?,
    openEdit: (String) -> Unit,
    focusRetryKey: Any?,
    onConsume: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    memoNotFoundMessage: String,
    onMainListFocusConsumed: () -> Unit,
) {
    LaunchedEffect(events, focusRetryKey) {
        events.forEach { event ->
            val action = event.payload
            val attempt =
                when (action) {
                    is MainViewModel.AppAction.OpenMemo -> {
                        if (resolveMemoById(action.memoId) != null) {
                            openEdit(action.memoId)
                        } else {
                            snackbarHostState.showSnackbar(unknownErrorMessage)
                        }
                        null
                    }

                    is MainViewModel.AppAction.FocusMemo -> {
                        val result = focusMemoInList(action.memoId)
                        if (result == MainScreenFocusAttempt.Missing) {
                            snackbarHostState.showSnackbar(memoNotFoundMessage)
                        }
                        result
                    }
                }
            if (shouldConsumeAppActionAfterHandling(action = action, attempt = attempt)) {
                if (action is MainViewModel.AppAction.FocusMemo) {
                    onMainListFocusConsumed()
                }
                onConsume(event.id)
            }
        }
    }
}

internal fun shouldConsumeAppActionAfterHandling(
    action: MainViewModel.AppAction,
    attempt: MainScreenFocusAttempt?,
): Boolean =
    when (action) {
        is MainViewModel.AppAction.FocusMemo ->
            attempt == MainScreenFocusAttempt.Placed || attempt == MainScreenFocusAttempt.Missing
        is MainViewModel.AppAction.OpenMemo -> true
    }

@Composable
fun HandlePendingSharedImageEvents(
    imageDirectory: String?,
    events: ImmutableList<PendingUiEvent<android.net.Uri>>,
    onImageDirectoryMissing: () -> Unit,
    onSaveImage: (android.net.Uri, (String) -> Unit, () -> Unit) -> Unit,
    onAppendImageMarkdown: (String) -> Unit,
    onEnsureEditorVisible: () -> Unit,
    onConsume: (Long) -> Unit,
) {
    LaunchedEffect(imageDirectory, events) {
        val pending = events.firstOrNull() ?: return@LaunchedEffect
        resolveSharedImageIntent(
            intentId = pending.id,
            imageDirectory = imageDirectory,
            onRequireImageDirectory = onImageDirectoryMissing,
            onSaveImage = { onResult, onError -> onSaveImage(pending.payload, onResult, onError) },
            onAppendImageMarkdown = onAppendImageMarkdown,
            onEnsureEditorVisible = onEnsureEditorVisible,
            onConsume = onConsume,
        )
    }
}

/**
 * Resolves one shared-image launch intent to a terminal state.
 *
 * Every outcome consumes the intent. A queue head that survives its own failure can never be
 * retried: this effect is keyed on the queue contents, so an unconsumed head means nothing will
 * ever move the intent again and the editor waits on markdown that will never arrive.
 */
internal fun resolveSharedImageIntent(
    intentId: Long,
    imageDirectory: String?,
    onRequireImageDirectory: () -> Unit,
    onSaveImage: (onResult: (String) -> Unit, onError: () -> Unit) -> Unit,
    onAppendImageMarkdown: (String) -> Unit,
    onEnsureEditorVisible: () -> Unit,
    onConsume: (Long) -> Unit,
) {
    if (imageDirectory == null) {
        onRequireImageDirectory()
        onConsume(intentId)
        return
    }
    onSaveImage(
        { path ->
            onAppendImageMarkdown(path)
            onEnsureEditorVisible()
            onConsume(intentId)
        },
        { onConsume(intentId) },
    )
}

@Composable
fun HandleErrorEffects(
    errorMessage: String?,
    editorErrorMessage: String?,
    recordingErrorMessage: String?,
    snackbarHostState: SnackbarHostState,
    clearMainError: () -> Unit,
    clearEditorError: () -> Unit,
    clearRecordingError: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            clearMainError()
        }
    }

    LaunchedEffect(editorErrorMessage) {
        editorErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            clearEditorError()
        }
    }

    LaunchedEffect(recordingErrorMessage) {
        recordingErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            clearRecordingError()
        }
    }
}
