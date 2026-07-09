package com.lomo.app.feature.search

import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.existingMemoEditorSurface

internal fun searchMemoEditorSurface(
    uiState: SearchScreenUiSnapshot,
    viewModel: SearchViewModel,
) =
    existingMemoEditorSurface(
        session =
            MemoEditorSessionState(
                imageDirectory = uiState.imageDirectory,
                rootPath = uiState.rootDirectory,
                imageMap = uiState.imageMap,
                dateFormat = uiState.dateFormat,
                timeFormat = uiState.timeFormat,
            ),
        toolbarToolOrder = uiState.inputToolbarToolOrder,
        onUpdateMemo = viewModel::updateMemo,
        onSaveImage = viewModel::saveImage,
        onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
    )
