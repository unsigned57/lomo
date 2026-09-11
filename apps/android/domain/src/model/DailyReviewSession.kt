package com.lomo.domain.model

import java.time.LocalDate

data class DailyReviewSession(
    val date: LocalDate,
    val seed: Long,
    val pageIndex: Int,
)
