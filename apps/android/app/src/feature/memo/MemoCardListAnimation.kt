package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.main.MemoListContent
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.common.ExitAnimationRegistry

@Composable
fun MemoCardList(
    pagedMemos: LazyPagingItems<MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    onMemoEdit: (Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onTodoClick: (Memo, MarkdownSourceSpan) -> Unit,
    listState: LazyListState? = null,
    freeTextCopyEnabled: Boolean = false,
    onTagClick: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(16.dp),
    showScrollbar: Boolean = true,
    exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel> =
        remember { ExitAnimationRegistry() },
) {
    val resolvedListState = listState ?: rememberLazyListState()
    MemoListContent(
        pagedMemos = pagedMemos,
        listState = resolvedListState,
        isRefreshing = false,
        onRefresh = {},
        pullToRefreshEnabled = false,
        listContentPadding = contentPadding,
        onTodoClick = onTodoClick,
        onReminderClick = { _, _ -> },
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onTagClick = onTagClick,
        onImageClick = onImageClick,
        exitAnimationRegistry = exitAnimationRegistry,
        onMemoDoubleClick = onMemoEdit,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        scrollbarEnabled = showScrollbar,
        onShowMemoMenu = onShowMenu,
    )
}
