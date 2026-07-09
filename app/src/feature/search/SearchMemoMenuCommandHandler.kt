package com.lomo.app.feature.search

import androidx.compose.runtime.Composable
import com.lomo.app.feature.memo.MemoEditorController
import com.lomo.app.feature.memo.MemoMenuCommandHandler
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.feature.memo.MemoMenuSelection

@Composable
internal fun rememberSearchMemoMenuCommandHandler(
    uiState: SearchScreenUiSnapshot,
    editorController: MemoEditorController,
    viewModel: SearchViewModel,
    lanShareEnabled: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    onRequestFocusMemo: (String) -> Unit,
    onNavigateToMain: () -> Unit,
): MemoMenuCommandHandler =
    rememberMemoMenuCommandHandler(
        presentationState =
            MemoMenuPresentationState(
                shareCardShowTime = uiState.shareCardShowTime,
                shareCardShowSignature = uiState.shareCardShowSignature,
                shareCardSignatureText = uiState.shareCardSignatureText,
                customFontPath = uiState.customFontPath,
                showJump = true,
                memoActionAutoReorderEnabled = uiState.memoActionAutoReorderEnabled,
                memoActionOrder = uiState.memoActionOrderForSearch,
            ),
        onEditMemo = editorController::openForEdit,
        onDeleteMemo = viewModel::deleteMemo,
        onLanShare =
            if (lanShareEnabled) {
                { request -> onNavigateToShare(request.content, request.timestamp) }
            } else {
                null
            },
        onJump = { state: MemoMenuSelection ->
            handleMemoJumpToMain(
                selection = state,
                requestFocusMemo = onRequestFocusMemo,
                navigateToMain = onNavigateToMain,
            )
        },
        onMemoActionInvoked = viewModel::recordMemoActionUsage,
        onMemoActionOrderChanged = viewModel.updateMemoActionOrder,
    )
