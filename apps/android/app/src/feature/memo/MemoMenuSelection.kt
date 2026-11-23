package com.lomo.app.feature.memo

import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuItemId
import com.lomo.ui.component.menu.MemoMenuState

data class MemoMenuSelection(val memo: Memo, val state: MemoMenuState, val anchoredAfterKey: String? = null)

fun memoMenuState(memo: Memo, imageUrls: List<String> = emptyList()): MemoMenuState = MemoMenuState(
    wordCount = memo.content.length,
    createdTime = memo.displayTimestamp(),
    content = memo.content,
    memoId = MemoMenuItemId(memo.id),
    imageUrls = imageUrls,
)

fun memoMenuSelection(
    memo: Memo,
    imageUrls: List<String> = emptyList(),
    anchoredAfterKey: String? = null,
): MemoMenuSelection = MemoMenuSelection(
    memo = memo,
    state =
    memoMenuState(
        memo = memo,
        imageUrls = imageUrls,
    ),
    anchoredAfterKey = anchoredAfterKey,
)
