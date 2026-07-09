package com.lomo.app.feature.memo

internal fun handleMemoJumpToMain(
    selection: MemoMenuSelection,
    requestFocusMemo: (String) -> Unit,
    navigateToMain: () -> Unit,
): Boolean {
    val memo = selection.memo
    requestFocusMemo(memo.id)
    navigateToMain()
    return true
}
