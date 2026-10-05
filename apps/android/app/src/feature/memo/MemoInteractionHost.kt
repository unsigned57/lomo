package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EditableMemoSnapshot
import com.lomo.domain.model.Memo
import com.lomo.domain.model.RecoverableDraftFailure
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoEditDraftRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.usecase.ReconcileDraftMediaUseCase
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Opens an editor from an identity, resolving the complete Rust snapshot at the boundary.
 * Collection rows intentionally carry only bounded previews; they are never accepted as an edit
 * session's content or CAS baseline.
 *
 * A persisted draft bound to the verified baseline is recovered together with its durable
 * [DraftId], so the reopened session still owns the media it staged before process death. A
 * stale or mismatched draft is cleared and its leases released — that media can never reach this
 * session's commits. [onFailure] receives typed failures the caller surfaces on its own error
 * channel; a recoverable draft-media failure still opens the session so the user can re-attach
 * or remove the dead references instead of losing the draft.
 */
@Composable
internal fun rememberFullMemoEditorOpener(
    controller: MemoEditorController,
    onFailure: (Throwable) -> Unit,
): (String) -> Unit {
    val repository = koinInject<MemoQueryRepository>()
    val editDraftRepository = koinInject<MemoEditDraftRepository>()
    val mediaRepository = koinInject<MediaRepository>()
    val reconcileDraftMedia = koinInject<ReconcileDraftMediaUseCase>()
    val scope = rememberCoroutineScope()
    val currentRepository = rememberUpdatedState(repository)
    val currentController = rememberUpdatedState(controller)
    val currentOnFailure = rememberUpdatedState(onFailure)
    return remember(scope, repository, editDraftRepository, mediaRepository, reconcileDraftMedia) {
        { memoId: String ->
            scope.launch {
                val memo =
                    currentRepository.value.getMemoById(memoId)
                        ?: error("Memo disappeared before editor open: $memoId")
                val snapshot =
                    try {
                        EditableMemoSnapshot.fromFullSnapshot(memo)
                    } catch (rejected: IllegalArgumentException) {
                        currentOnFailure.value(rejected)
                        return@launch
                    }
                val draft = editDraftRepository.read()
                val baseline = snapshot.baseline
                val recoveredContent =
                    draft
                        ?.takeIf {
                            it.matchesBaseline(
                                currentMemoId = memo.id,
                                currentRevision = baseline.contentRevision,
                                currentFingerprint = baseline.fileFingerprint,
                            )
                        }
                val draftId =
                    when {
                        recoveredContent != null -> recoveredContent.draftId
                        draft != null -> {
                            // Baseline moved on: the stale draft is discarded and its staged
                            // leases released; the session starts under a fresh identity.
                            editDraftRepository.clear(draft.memoId)
                            mediaRepository.releaseDraftLeases(draft.draftId)
                            DraftId.mint()
                        }
                        else -> DraftId.mint()
                    }
                try {
                    reconcileDraftMedia(draftId)
                } catch (recoverable: RecoverableDraftFailure) {
                    currentOnFailure.value(recoverable)
                }
                currentController.value.openForEditContent(
                    MemoEditSession(snapshot = snapshot, draftId = draftId),
                    recoveredContent?.content ?: memo.content,
                )
            }
        }
    }
}

@Composable
fun MemoInteractionHost(
    menuCommandHandler: MemoMenuCommandHandler,
    editorSurface: MemoEditorSurface,
    onEditorOpenFailure: (Throwable) -> Unit,
    controller: MemoEditorController = rememberMemoEditorController(),
    content: @Composable (
        showMenu: (MemoMenuSelection) -> Unit,
        openEditor: (Memo) -> Unit,
    ) -> Unit,
) {
    val openFullMemoEditor = rememberFullMemoEditorOpener(controller, onEditorOpenFailure)
    MemoMenuBinder(commandHandler = menuCommandHandler) { showMenu ->
        content(showMenu) { memo -> openFullMemoEditor(memo.id) }

        MemoEditorSheetHost(
            controller = controller,
            surface = editorSurface,
        )
    }
}
