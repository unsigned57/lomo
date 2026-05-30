package com.lomo.domain.model

import java.time.LocalDate

data class MemoSidebarStatistics(
    val memoCount: Int,
    val memoCountByDate: Map<LocalDate, Int>,
    val tagCounts: List<MemoTagCount>,
)
