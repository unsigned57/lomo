package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoEditDraft
import com.lomo.domain.model.markdown.MarkdownRenderContractException
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoEditDraftRepository
import com.lomo.ui.component.markdown.MarkdownRenderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val MEMO_EDITOR_DRAFT_DEBOUNCE_MILLIS = 500L

@Composable
internal fun MemoEditorDraftAutosaveEffect(
    controller: MemoEditorController,
    repository: MemoEditDraftRepository,
) {
    LaunchedEffect(controller, repository) {
        var lastPersistedMemoId: String? = null
        snapshotFlow {
            MemoEditDraftAutosaveState(
                memo = controller.editingMemo,
                text = controller.inputValue.text,
                isVisible = controller.isVisible,
            )
        }.distinctUntilChanged()
            .collectLatest { state ->
                val memo = state.memo
                val memoId = memo?.id
                if (lastPersistedMemoId != null && lastPersistedMemoId != memoId) {
                    repository.clear(checkNotNull(lastPersistedMemoId))
                    lastPersistedMemoId = null
                }
                if (!state.isVisible) {
                    lastPersistedMemoId?.let { persistedMemoId ->
                        repository.clear(persistedMemoId)
                        lastPersistedMemoId = null
                    }
                    return@collectLatest
                }
                if (memo == null) return@collectLatest
                val revision = memo.contentRevision
                val fingerprint = memo.fileFingerprint?.takeIf(String::isNotBlank)
                if (revision == null || fingerprint == null) return@collectLatest
                delay(MEMO_EDITOR_DRAFT_DEBOUNCE_MILLIS)
                repository.write(
                    MemoEditDraft(
                        memoId = memo.id,
                        baselineRevision = revision,
                        baselineFingerprint = fingerprint,
                        content = state.text,
                    ),
                )
                lastPersistedMemoId = memo.id
            }
    }
}

@Composable
internal fun rememberMemoEditorPreviewState(
    controller: MemoEditorController,
    repository: MarkdownWorkspaceRepository,
    session: MemoEditorSessionState,
): MarkdownRenderState {
    val imageContentResolver = remember { com.lomo.app.feature.main.MemoUiImageContentResolver() }
    var previewState by remember { mutableStateOf<MarkdownRenderState>(MarkdownRenderState.Pending) }
    LaunchedEffect(
        controller,
        controller.inputValue.text,
        controller.displayMode,
        repository,
        session,
    ) {
        if (!shouldRenderMemoEditorPreview(controller.displayMode)) {
            previewState = MarkdownRenderState.Pending
            return@LaunchedEffect
        }
        delay(MEMO_EDITOR_PREVIEW_DEBOUNCE_MILLIS)
        previewState = MarkdownRenderState.Pending
        previewState =
            withContext(Dispatchers.Default) {
                try {
                    MarkdownRenderState.Ready(
                        imageContentResolver.resolveRenderDocumentImages(
                            document = repository.renderMarkdown(controller.inputValue.text),
                            rootPath = session.rootPath,
                            imagePath = session.imageDirectory,
                            imageMap = session.imageMap,
                        ),
                    )
                } catch (failure: MarkdownRenderContractException) {
                    MarkdownRenderState.Failed(failure.code)
                } catch (failure: IllegalStateException) {
                    if (failure is kotlinx.coroutines.CancellationException) {
                        throw failure
                    }
                    MarkdownRenderState.Failed("workspace_not_ready")
                }
            }
    }
    return previewState
}

private data class MemoEditDraftAutosaveState(
    val memo: Memo?,
    val text: String,
    val isVisible: Boolean,
)
