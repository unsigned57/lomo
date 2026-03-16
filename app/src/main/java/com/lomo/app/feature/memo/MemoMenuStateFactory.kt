package com.lomo.app.feature.memo

import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState
import com.lomo.ui.util.formatAsDateTime

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
        isPinned = memo.isPinned,
        memo = memo,
        imageUrls = imageUrls,
    )
