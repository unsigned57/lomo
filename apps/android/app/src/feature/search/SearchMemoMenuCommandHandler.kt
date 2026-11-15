package com.lomo.app.feature.search

import androidx.compose.runtime.Composable
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoMenuCommandHandler
import com.lomo.app.feature.memo.MemoMenuContextAction
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberFullMemoEditorOpener
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler

@Composable
internal fun rememberSearchMemoMenuCommandHandler(
    uiState: SearchScreenUiSnapshot,
    editorController: MemoEditorController,
    onReportError: (Throwable) -> Unit,
    onDeleteMemo: (com.lomo.domain.model.Memo, String?) -> Unit,
    lanShareEnabled: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    onRequestFocusMemo: (String) -> Unit,
    onNavigateToMain: () -> Unit,
    onVersionHistory: ((MemoMenuSelection) -> Unit)? = null,
): MemoMenuCommandHandler {
    val openFullMemoEditor = rememberFullMemoEditorOpener(editorController, onReportError)
    return rememberMemoMenuCommandHandler(
        presentationState =
        MemoMenuPresentationState(
            shareCardShowTime = uiState.shareCardShowTime,
            shareCardShowSignature = uiState.shareCardShowSignature,
            shareCardSignatureText = uiState.shareCardSignatureText,
            customFontPath = uiState.customFontPath,
        ),
        onEditMemo = openFullMemoEditor,
        onDeleteMemo = onDeleteMemo,
        onLanShare =
        if (lanShareEnabled) {
            { request -> onNavigateToShare(request.content, request.timestamp) }
        } else {
            null
        },
        contextAction =
        MemoMenuContextAction.Jump { state ->
            handleMemoJumpToMain(
                selection = state,
                requestFocusMemo = onRequestFocusMemo,
                navigateToMain = onNavigateToMain,
            )
        },
        onVersionHistory = onVersionHistory,
    )
}
