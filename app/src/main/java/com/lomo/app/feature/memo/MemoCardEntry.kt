package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
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
        onDoubleClick =
            if (doubleTapEditEnabled) {
                { onMemoEdit(memo) }
            } else {
                null
            },
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
