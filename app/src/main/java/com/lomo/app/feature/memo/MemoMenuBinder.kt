package com.lomo.app.feature.memo

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.lomo.app.util.ShareUtils
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuHost
import com.lomo.ui.component.menu.MemoMenuState

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
    content: @Composable (showMenu: (MemoMenuState) -> Unit) -> Unit,
) {
    val context = LocalContext.current

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
            ShareUtils.shareMemoAsImage(
                context = context,
                content = state.content,
                style = shareCardStyle,
                showTime = shareCardShowTime,
                timestamp = memo?.timestamp,
                tags = memo?.tags.orEmpty(),
                activeDayCount = activeDayCount,
            )
        },
        onLanShare = { state ->
            val memo = state.memo as? Memo
            if (memo != null) {
                onLanShare(memo.content, memo.timestamp)
            }
        },
    ) { showMenu ->
        content(showMenu)
    }
}
