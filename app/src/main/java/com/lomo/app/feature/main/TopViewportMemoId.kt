package com.lomo.app.feature.main

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
internal fun rememberTopViewportMemoId(
    listState: LazyListState,
): String? {
    val topViewportMemoId by
        remember(listState) {
            derivedStateOf {
                resolveTopViewportMemoId(listState.layoutInfo)
            }
        }
    return topViewportMemoId
}

internal fun resolveTopViewportMemoId(
    layoutInfo: LazyListLayoutInfo,
): String? =
    layoutInfo.visibleItemsInfo
        .firstOrNull { item -> item.isRealTopViewportItem(layoutInfo) }
        ?.key as? String

private fun LazyListItemInfo.isRealTopViewportItem(
    layoutInfo: LazyListLayoutInfo,
): Boolean =
    size > 0 &&
        offset >= layoutInfo.viewportStartOffset &&
        offset < layoutInfo.viewportEndOffset
