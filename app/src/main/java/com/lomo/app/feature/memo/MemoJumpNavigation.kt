package com.lomo.app.feature.memo

import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.component.menu.memoAs

internal fun handleMemoJumpToMain(
    state: MemoMenuState,
    requestFocusMemo: (String) -> Unit,
    navigateToMain: () -> Unit,
): Boolean {
    val memo = state.memoAs<Memo>() ?: return false
    requestFocusMemo(memo.id)
    navigateToMain()
    return true
}
