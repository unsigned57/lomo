package com.lomo.app.feature.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.common.SkeletonMemoItem
import com.lomo.ui.component.common.lomoListItemMotion

internal const val PAGING_PLACEHOLDER_KEY_PREFIX = "paging-placeholder-"

@Composable
internal fun MemoPagedListPlaceholderRow(
    lazyItemScope: LazyItemScope,
    index: Int,
    itemCount: Int,
) {
    val bottomSpacing = if (index == itemCount - 1) 0.dp else MEMO_LIST_ITEM_SPACING
    SkeletonMemoItem(
        modifier =
            Modifier
                .lomoListItemMotion(lazyItemScope)
                .fillMaxWidth()
                .padding(bottom = bottomSpacing),
    )
}
