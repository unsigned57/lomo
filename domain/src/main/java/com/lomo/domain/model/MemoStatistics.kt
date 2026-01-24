
package com.lomo.domain.model

data class MemoStatistics(
    val totalMemos: Int,
    val tagCounts: List<TagCount>,
    val timestamps: List<Long>,
)
