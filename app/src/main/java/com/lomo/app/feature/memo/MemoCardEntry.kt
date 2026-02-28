package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.menu.MemoMenuState

@Composable
fun MemoCardEntry(
    uiModel: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    modifier: Modifier = Modifier,
    onMemoClick: ((Memo) -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    onImageClick: (String) -> Unit = {},
) {
    val memo = uiModel.memo

    MemoCard(
        content = memo.content,
        processedContent = uiModel.processedContent,
        precomputedNode = uiModel.markdownNode,
        timestamp = memo.timestamp,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        tags = uiModel.tags,
        modifier = modifier,
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
        onMenuClick = {
            onShowMenu(
                memoMenuState(
                    memo = memo,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                ),
            )
        },
        menuContent = {},
    )
}
