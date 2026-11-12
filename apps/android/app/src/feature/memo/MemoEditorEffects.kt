package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.lomo.domain.model.MemoEditDraft
import com.lomo.domain.model.markdown.MarkdownRenderContractException
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoEditDraftRepository
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.ui.component.markdown.MarkdownRenderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private const val MEMO_EDITOR_DRAFT_DEBOUNCE_MILLIS = 500L

@Composable
internal fun MemoEditorDraftAutosaveEffect(controller: MemoEditorController, repository: MemoEditDraftRepository) {
    LaunchedEffect(controller, repository) {
        var lastPersistedMemoId: String? = null
        snapshotFlow {
            MemoEditDraftAutosaveState(
                session = controller.editingSession,
                text = controller.inputValue.text,
                isVisible = controller.isVisible,
            )
        }.distinctUntilChanged()
            .collectLatest { state ->
                val session = state.session
                val memoId = session?.run { snapshot.memo.id }
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
                if (session == null) return@collectLatest
                val baseline = session.snapshot.baseline
                delay(MEMO_EDITOR_DRAFT_DEBOUNCE_MILLIS)
                repository.write(
                    MemoEditDraft(
                        draftId = session.draftId,
                        memoId = baseline.memoId,
                        baselineRevision = baseline.contentRevision,
                        baselineFingerprint = baseline.fileFingerprint,
                        content = state.text,
                    ),
                )
                lastPersistedMemoId = baseline.memoId
            }
    }
}

@Composable
internal fun rememberMemoEditorContentState(
    controller: MemoEditorController,
    repository: MarkdownWorkspaceRepository,
    session: MemoEditorSessionState,
): MemoEditorContentState {
    val imageContentResolver = remember { com.lomo.app.feature.main.MemoUiImageContentResolver() }
    val dispatcherProvider = org.koin.compose.koinInject<DispatcherProvider>()
    val tokenFactory = org.koin.compose.koinInject<com.lomo.domain.repository.ReminderTokenFactory>()
    var contentState by remember {
        mutableStateOf(MemoEditorContentState("", MarkdownRenderState.Pending))
    }
    val sourceText = controller.inputValue.text
    val previewRequested = shouldRenderMemoEditorPreview(controller.displayMode)
    LaunchedEffect(controller, sourceText, previewRequested, repository, session, tokenFactory) {
        if (previewRequested) delay(MEMO_EDITOR_PREVIEW_DEBOUNCE_MILLIS)
        contentState = withContext(dispatcherProvider.default) {
            try {
                val document = repository.renderMarkdown(sourceText)
                val reminders = projectMemoEditorReminders(
                    sourceText,
                    document,
                    tokenFactory::dueAt,
                    session.dateFormat,
                    session.timeFormat,
                )
                val previewDocument = if (previewRequested) {
                    imageContentResolver.resolveRenderDocumentImages(
                        document,
                        session.rootPath,
                        session.imageDirectory,
                        session.imageMap,
                    )
                } else {
                    document
                }
                MemoEditorContentState(sourceText, MarkdownRenderState.Ready(previewDocument), reminders)
            } catch (failure: MarkdownRenderContractException) {
                MemoEditorContentState(sourceText, MarkdownRenderState.Failed(failure.code))
            } catch (failure: IllegalStateException) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                MemoEditorContentState(sourceText, MarkdownRenderState.Failed("workspace_not_ready"))
            }
        }
    }
    return contentState
}

private data class MemoEditDraftAutosaveState(val session: MemoEditSession?, val text: String, val isVisible: Boolean)
