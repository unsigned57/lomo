package com.lomo.app.feature.main

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lomo.ui.component.common.LazyListItemEntranceState
import com.lomo.ui.component.common.LazyListMotionState
import com.lomo.ui.component.common.SkeletonMemoItem
import com.lomo.ui.component.common.lazyListMotionItem

internal const val PAGING_PLACEHOLDER_KEY_PREFIX = "paging-placeholder-"

@Composable
internal fun MemoPagedListPlaceholderRow(
    lazyItemScope: LazyItemScope,
    index: Int,
    itemCount: Int,
    listMotionState: LazyListMotionState,
    structureMotionActive: Boolean,
) {
    val bottomSpacing = if (index == itemCount - 1) 0.dp else MEMO_LIST_ITEM_SPACING
    val itemKey = "$PAGING_PLACEHOLDER_KEY_PREFIX$index"
    SkeletonMemoItem(
        modifier =
            Modifier
                .lazyListMotionItem(
                    lazyItemScope = lazyItemScope,
                    itemKey = itemKey,
                    motionState = listMotionState,
                    entranceState = LazyListItemEntranceState.Settled,
                    structureMotionActive = structureMotionActive,
                )
                .fillMaxWidth()
                .padding(bottom = bottomSpacing),
    )
}
