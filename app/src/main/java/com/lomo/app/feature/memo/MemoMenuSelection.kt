package com.lomo.app.feature.memo

import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuItemId
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.util.formatAsDateTime

data class MemoMenuSelection(
    val memo: Memo,
    val state: MemoMenuState,
    val anchoredAfterKey: String? = null,
)

fun memoMenuState(
    memo: Memo,
    dateFormat: String,
    timeFormat: String,
    imageUrls: List<String> = emptyList(),
): MemoMenuState =
    MemoMenuState(
        wordCount = memo.content.length,
        createdTime = memo.timestamp.formatAsDateTime(dateFormat, timeFormat),
        content = memo.content,
        memoId = MemoMenuItemId(memo.id),
        imageUrls = imageUrls,
    )

fun memoMenuSelection(
    memo: Memo,
    dateFormat: String,
    timeFormat: String,
    imageUrls: List<String> = emptyList(),
    anchoredAfterKey: String? = null,
): MemoMenuSelection =
    MemoMenuSelection(
        memo = memo,
        state =
            memoMenuState(
                memo = memo,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                imageUrls = imageUrls,
            ),
        anchoredAfterKey = anchoredAfterKey,
    )

