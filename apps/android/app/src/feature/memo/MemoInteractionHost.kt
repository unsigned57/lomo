package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoEditDraftRepository
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Opens an editor from an identity, resolving the complete Rust snapshot at the boundary.
 * Collection rows intentionally carry only bounded previews; they are never accepted as an edit
 * session's content or CAS baseline.
 */
@Composable
internal fun rememberFullMemoEditorOpener(controller: MemoEditorController): (String) -> Unit {
    val repository = koinInject<MemoQueryRepository>()
    val editDraftRepository = koinInject<MemoEditDraftRepository>()
    val scope = rememberCoroutineScope()
    val currentRepository = rememberUpdatedState(repository)
    val currentController = rememberUpdatedState(controller)
    return remember(scope, repository, editDraftRepository) {
        { memoId: String ->
            scope.launch {
                val memo =
                    currentRepository.value.getMemoById(memoId)
                        ?: error("Memo disappeared before editor open: $memoId")
                val draft = editDraftRepository.read()
                val currentRevision = memo.contentRevision
                val currentFingerprint = memo.fileFingerprint
                val recoveredContent =
                    draft
                        ?.takeIf {
                            currentRevision != null &&
                                !currentFingerprint.isNullOrBlank() &&
                                it.matchesBaseline(
                                    currentMemoId = memo.id,
                                    currentRevision = currentRevision,
                                    currentFingerprint = currentFingerprint,
                                )
                        }
                if (draft != null && recoveredContent == null) {
                    editDraftRepository.clear(draft.memoId)
                }
                currentController.value.openForEditContent(
                    memo,
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
    controller: MemoEditorController = rememberMemoEditorController(),
    content: @Composable (
        showMenu: (MemoMenuSelection) -> Unit,
        openEditor: (Memo) -> Unit,
    ) -> Unit,
) {
    val openFullMemoEditor = rememberFullMemoEditorOpener(controller)
    MemoMenuBinder(commandHandler = menuCommandHandler) { showMenu ->
        content(showMenu) { memo -> openFullMemoEditor(memo.id) }

        MemoEditorSheetHost(
            controller = controller,
            surface = editorSurface,
        )
    }
}
