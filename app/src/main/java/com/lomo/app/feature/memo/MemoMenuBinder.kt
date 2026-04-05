package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.util.LocalShareUtils
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoActionId
import com.lomo.ui.component.menu.MemoMenuHost
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.coroutines.launch

@Composable
fun MemoMenuBinder(
    shareCardShowTime: Boolean,
    activeDayCount: Int,
    onEditMemo: (Memo) -> Unit,
    onDeleteMemo: (Memo) -> Unit,
    onLanShare: (
        content: String,
        timestamp: Long,
    ) -> Unit,
    onTogglePin: ((Memo, Boolean) -> Unit)? = null,
    onJump: ((MemoMenuState) -> Unit)? = null,
    onVersionHistory: ((MemoMenuState) -> Unit)? = null,
    showJump: Boolean = false,
    showVersionHistory: Boolean = false,
    memoActionAutoReorderEnabled: Boolean = true,
    memoActionOrder: List<String> = emptyList(),
    onMemoActionInvoked: (MemoActionId) -> Unit = {},
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val shareUtils = LocalShareUtils.current
    val scope = rememberCoroutineScope()

    MemoMenuHost(
        onEdit = { state -> state.withMemo(onEditMemo) },
        onDelete = { state -> state.withMemo(onDeleteMemo) },
        onShareImage = { state ->
            scope.launch {
                shareMemoAsImage(
                    state = state,
                    context = context,
                    shareUtils = shareUtils,
                    shareCardShowTime = shareCardShowTime,
                    activeDayCount = activeDayCount,
                )
            }
        },
        onShareText = { state ->
            shareUtils.shareMemoText(
                context = context,
                content = state.content,
            )
        },
        onLanShare = { state -> state.withMemo { memo -> onLanShare(memo.content, memo.timestamp) } },
        onTogglePin =
            if (onTogglePin != null) {
                { state -> state.withMemo { memo -> onTogglePin(memo, !state.isPinned) } }
            } else {
                null
            },
        onJump = onJump,
        onHistory = onVersionHistory,
        showJump = showJump,
        showHistory = showVersionHistory,
        memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
        memoActionOrder = memoActionOrder,
        onMemoActionInvoked = onMemoActionInvoked,
        benchmarkRootTag = BenchmarkAnchorContract.MEMO_MENU_ROOT,
        benchmarkActionAnchorForId = ::benchmarkMemoActionAnchor,
    ) { showMenu ->
        content(showMenu)
    }
}

private fun benchmarkMemoActionAnchor(actionId: MemoActionId): String? =
    when (actionId) {
        MemoActionId.HISTORY -> BenchmarkAnchorContract.MEMO_ACTION_HISTORY
        MemoActionId.EDIT -> BenchmarkAnchorContract.MEMO_ACTION_EDIT
        MemoActionId.DELETE -> BenchmarkAnchorContract.MEMO_ACTION_DELETE
        else -> null
    }

private suspend fun shareMemoAsImage(
    state: MemoMenuState,
    context: android.content.Context,
    shareUtils: com.lomo.app.util.ShareUtils,
    shareCardShowTime: Boolean,
    activeDayCount: Int,
) {
    val memo = state.memo as? Memo
    shareUtils.shareMemoAsImage(
        context = context,
        content = state.content,
        showTime = shareCardShowTime,
        timestamp = memo?.timestamp,
        tags = memo?.tags.orEmpty(),
        activeDayCount = activeDayCount,
        resolvedImagePaths = state.imageUrls,
    )
}

private inline fun MemoMenuState.withMemo(block: (Memo) -> Unit) {
    (memo as? Memo)?.let(block)
}
