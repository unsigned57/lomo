package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
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
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    modifier: Modifier = Modifier,
    onMemoClick: ((Memo) -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    onImageClick: (String) -> Unit = {},
    onReminderClick: (com.lomo.domain.model.ReminderMarker) -> Unit = {},
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val memo = uiModel.memo

    MemoCard(
        content = memo.content,
        processedContent = uiModel.processedContent,
        precomputedRenderPlan = uiModel.precomputedRenderPlan,
        timestamp = memo.timestamp,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        isPinned = memo.isPinned,
        tags = uiModel.tags,
        reminders = uiModel.reminders,
        modifier = modifier.benchmarkAnchor(BenchmarkAnchorContract.memoCard(memo.id)),
        allowFreeTextCopy = freeTextCopyEnabled,
        expandOnClick = true,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        onClick = { onMemoClick?.invoke(memo) },
        onDoubleClick =
            if (doubleTapEditEnabled) {
                { onMemoEdit(memo) }
            } else {
                null
            },
        onTagClick = onTagClick,
        onTodoClick = onTodoClick,
        onImageClick = onImageClick,
        onReminderClick = onReminderClick,
        shouldShowExpand = uiModel.shouldShowExpand,
        collapsedSummary = uiModel.collapsedSummary,
        menuButtonModifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.memoMenu(memo.id)),
        onMenuClick = {
            onShowMenu(
                memoMenuSelection(
                    memo = memo,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    imageUrls = uiModel.imageUrls,
                ),
            )
        },
        menuContent = {},
        mediaPresentationResolver = MemoMarkdownMediaAdapter.resolver,
        mediaContent = MemoMarkdownMediaAdapter.content,
    )
}
