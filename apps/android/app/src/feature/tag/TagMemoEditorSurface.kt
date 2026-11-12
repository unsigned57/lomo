package com.lomo.app.feature.tag

import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.existingMemoEditorSurface

internal fun tagMemoEditorSurface(
    viewModel: TagFilterViewModel,
    rootDirectory: String?,
    imageDirectory: String?,
    stableImageMap: kotlinx.collections.immutable.ImmutableMap<String, android.net.Uri>,
    appPreferences: com.lomo.app.feature.preferences.AppPreferencesState,
) = existingMemoEditorSurface(
    session =
    MemoEditorSessionState(
        imageDirectory = imageDirectory,
        ownerDraftId = viewModel.ownerDraftId,
        rootPath = rootDirectory,
        imageMap = stableImageMap,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
    ),
    toolbarToolOrder = appPreferences.inputToolbarToolOrder,
    onUpdateMemo = viewModel::submitMemoUpdate,
    submissionState = viewModel.editorSubmissionState,
    onSaveImages = viewModel::saveImages,
    onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
)
