package com.lomo.app.feature.main

internal fun updateExpandedMemoIds(
    expandedMemoIds: Set<String>,
    memoId: String,
    isExpanded: Boolean,
): Set<String> =
    if (isExpanded) {
        expandedMemoIds + memoId
    } else {
        expandedMemoIds - memoId
    }
