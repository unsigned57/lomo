package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.lomo.app.util.LocalShareUtils
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuHost
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.coroutines.launch

@Composable
fun MemoMenuBinder(
    shareCardStyle: String,
    shareCardShowTime: Boolean,
    activeDayCount: Int,
    onEditMemo: (Memo) -> Unit,
    onDeleteMemo: (Memo) -> Unit,
    onLanShare: (
        content: String,
        timestamp: Long,
    ) -> Unit,
    onVersionHistory: ((MemoMenuState) -> Unit)? = null,
    showVersionHistory: Boolean = false,
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val shareUtils = LocalShareUtils.current
    val scope = rememberCoroutineScope()

    MemoMenuHost(
        onEdit = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                onEditMemo(memo)
            }
        },
        onDelete = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                onDeleteMemo(memo)
            }
        },
        onShare = { state ->
            val memo = state.memo as? Memo
            scope.launch {
                shareUtils.shareMemoAsImage(
                    context = context,
                    content = state.content,
                    hostView = hostView,
                    style = shareCardStyle,
                    showTime = shareCardShowTime,
                    timestamp = memo?.timestamp,
                    tags = memo?.tags.orEmpty(),
                    activeDayCount = activeDayCount,
                )
            }
        },
        onLanShare = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                onLanShare(memo.content, memo.timestamp)
            }
        },
        onHistory = onVersionHistory,
        showHistory = showVersionHistory,
    ) { showMenu ->
        content(showMenu)
    }
}
