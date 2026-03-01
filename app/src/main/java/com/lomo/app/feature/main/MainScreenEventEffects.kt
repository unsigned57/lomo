package com.lomo.app.feature.main

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lomo.domain.model.Memo

@Composable
fun MainScreenEventEffectsHost(
    sharedContentEvents: List<PendingUiEvent<MainViewModel.SharedContent>>,
    appActionEvents: List<PendingUiEvent<MainViewModel.AppAction>>,
    pendingSharedImageEvents: List<PendingUiEvent<Uri>>,
    imageDirectory: String?,
    errorMessage: String?,
    editorErrorMessage: String?,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
    onAppendMarkdown: (String) -> Unit,
    onAppendImageMarkdown: (String) -> Unit,
    onEnsureEditorVisible: () -> Unit,
    onOpenCreateMemo: () -> Unit,
    onOpenEditMemo: (Memo) -> Unit,
    onResolveMemoById: suspend (String) -> Memo?,
    onSaveImage: (Uri, (String) -> Unit) -> Unit,
    onRequireImageDirectory: () -> Unit,
    onConsumeSharedContentEvent: (Long) -> Unit,
    onConsumeAppActionEvent: (Long) -> Unit,
    onConsumePendingSharedImageEvent: (Long) -> Unit,
    onClearMainError: () -> Unit,
    onClearEditorError: () -> Unit,
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
        resolveMemoById = onResolveMemoById,
        openCreate = onOpenCreateMemo,
        openEdit = onOpenEditMemo,
        onConsume = onConsumeAppActionEvent,
        snackbarHostState = snackbarHostState,
        unknownErrorMessage = unknownErrorMessage,
    )

    HandlePendingSharedImageEvents(
        imageDirectory = imageDirectory,
        events = pendingSharedImageEvents,
        onImageDirectoryMissing = onRequireImageDirectory,
        onSaveImage = onSaveImage,
        onImageSaved = { path ->
            onAppendImageMarkdown(path)
            onEnsureEditorVisible()
        },
        onConsume = onConsumePendingSharedImageEvent,
    )

    HandleErrorEffects(
        errorMessage = errorMessage,
        editorErrorMessage = editorErrorMessage,
        snackbarHostState = snackbarHostState,
        clearMainError = onClearMainError,
        clearEditorError = onClearEditorError,
    )
}

@Composable
fun HandleSharedContentEvents(
    events: List<PendingUiEvent<MainViewModel.SharedContent>>,
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
fun HandleAppActionEvents(
    events: List<PendingUiEvent<MainViewModel.AppAction>>,
    resolveMemoById: suspend (String) -> com.lomo.domain.model.Memo?,
    openCreate: () -> Unit,
    openEdit: (com.lomo.domain.model.Memo) -> Unit,
    onConsume: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    unknownErrorMessage: String,
) {
    LaunchedEffect(events) {
        events.forEach { event ->
            when (val action = event.payload) {
                MainViewModel.AppAction.CreateMemo -> openCreate()
                is MainViewModel.AppAction.OpenMemo -> {
                    val memo = resolveMemoById(action.memoId)
                    if (memo != null) {
                        openEdit(memo)
                    } else {
                        snackbarHostState.showSnackbar(unknownErrorMessage)
                    }
                }
            }
            onConsume(event.id)
        }
    }
}

@Composable
fun HandlePendingSharedImageEvents(
    imageDirectory: String?,
    events: List<PendingUiEvent<android.net.Uri>>,
    onImageDirectoryMissing: () -> Unit,
    onSaveImage: (android.net.Uri, (String) -> Unit) -> Unit,
    onImageSaved: (String) -> Unit,
    onConsume: (Long) -> Unit,
) {
    LaunchedEffect(imageDirectory, events) {
        val pending = events.firstOrNull() ?: return@LaunchedEffect
        if (imageDirectory == null) {
            onImageDirectoryMissing()
            return@LaunchedEffect
        }

        onSaveImage(pending.payload) { path ->
            onImageSaved(path)
            onConsume(pending.id)
        }
    }
}

@Composable
fun HandleErrorEffects(
    errorMessage: String?,
    editorErrorMessage: String?,
    snackbarHostState: SnackbarHostState,
    clearMainError: () -> Unit,
    clearEditorError: () -> Unit,
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
}
