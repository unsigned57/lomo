package com.lomo.app.feature.memo

import android.net.Uri
import androidx.compose.runtime.Stable
import com.lomo.domain.model.DraftId
import kotlinx.coroutines.flow.StateFlow

/** Live reactive streams and current draft identity exposed by [MemoEditorViewModel] to UI hosts. */
@Stable
internal data class MemoEditorHostFlows(
    val draftText: StateFlow<String>,
    val errorMessage: StateFlow<String?>,
    val submissionState: StateFlow<MemoEditorSubmissionState>,
    val getOwnerDraftId: () -> DraftId,
)

/** Non-ViewModel media, draft, and error callbacks exposed by [MemoEditorViewModel] to UI hosts. */
@Stable
internal data class MemoEditorHostActions(
    val onSaveDraft: (String) -> Unit,
    val onSaveImages: (List<Uri>, DraftId, (List<String>) -> Unit, (() -> Unit)?) -> Unit,
    val onSaveImage: (Uri, DraftId, (String) -> Unit, (() -> Unit)?) -> Unit,
    val onTrackVoiceMarkdown: (String) -> Unit,
    val onDiscardInputs: (DraftId) -> Unit,
    val onReportError: (Throwable) -> Unit,
    val onClearError: () -> Unit,
)

/** Cohesive host binding surface for [MemoEditorViewModel] without forwarding the ViewModel itself. */
@Stable
internal data class MemoEditorHostBindings(
    val flows: MemoEditorHostFlows,
    val submissions: MemoEditorCommitCoordinator,
    val actions: MemoEditorHostActions,
)
