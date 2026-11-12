package com.lomo.app.feature.main

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lomo.ui.component.common.SkeletonMemoItem
import com.lomo.ui.theme.adaptiveContentWidth

internal const val PAGING_PLACEHOLDER_KEY_PREFIX = "paging-placeholder-"

@Composable
internal fun MemoPagedListPlaceholderRow() {
    SkeletonMemoItem(
        modifier =
        Modifier
            .adaptiveContentWidth(MemoFeedMaxWidth)
            .padding(bottom = MEMO_LIST_ITEM_SPACING),
    )
}
