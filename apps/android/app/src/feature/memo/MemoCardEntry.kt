package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.presentation.markdown.MemoMarkdownMediaAdapter
import com.lomo.domain.model.Memo
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.card.MemoCard

@Composable
fun MemoCardEntry(
    uiModel: MemoUiModel,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onTodoClick: ((com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit)?,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMemoClick: ((Memo) -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    onReminderClick: (com.lomo.domain.model.ReminderMarker) -> Unit = {},
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    anchoredAfterKey: String? = null,
) {
    val memo = uiModel.memo
    val stableOnClick =
        remember(memo, onMemoClick) {
            {
                onMemoClick?.invoke(memo)
                Unit
            }
        }
    val stableOnDoubleClick =
        remember(memo, doubleTapEditEnabled, onMemoEdit) {
            if (doubleTapEditEnabled) {
                { onMemoEdit(memo) }
            } else {
                null
            }
        }
    val stableOnMenuClick =
        remember(memo, uiModel.imageUrls, anchoredAfterKey, onShowMenu) {
            {
                onShowMenu(
                    memoMenuSelection(
                        memo = memo,
                        imageUrls = uiModel.imageUrls,
                        anchoredAfterKey = anchoredAfterKey,
                    ),
                )
            }
        }

    MemoCard(
        content = memo.content,
        processedContent = uiModel.processedContent,
        renderDocument = uiModel.renderDocument,
        presentationPlan = uiModel.presentationPlan,
        timestamp = memo.timestamp,
        displayTimestamp = memo.displayTimestamp(),
        isPinned = memo.isPinned,
        isPending = memo.isPending,
        tags = uiModel.tags,
        reminders = uiModel.reminders,
        modifier = modifier
            .benchmarkAnchor(BenchmarkAnchorContract.memoCard(memo.id, isPending = memo.isPending)),
        allowFreeTextCopy = freeTextCopyEnabled,
        expandOnClick = true,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        onClick = stableOnClick,
        onDoubleClick = stableOnDoubleClick,
        onTagClick = onTagClick,
        onTodoClick = onTodoClick,
        onImageClick = onImageClick,
        onReminderClick = onReminderClick,
        shouldShowExpand = uiModel.shouldShowExpand,
        collapsedSummary = uiModel.collapsedSummary,
        menuButtonModifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.memoMenu(memo.id)),
        onMenuClick = stableOnMenuClick,
        menuContent = {},
        mediaPresentationResolver = MemoMarkdownMediaAdapter.resolver,
        mediaContent = MemoMarkdownMediaAdapter.content,
    )
}
