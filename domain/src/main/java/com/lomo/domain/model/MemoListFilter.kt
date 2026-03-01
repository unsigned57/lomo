package com.lomo.domain.model

import java.time.LocalDate

enum class MemoSortOption {
    CREATED_TIME,
    UPDATED_TIME,
}

data class MemoListFilter(
    val sortOption: MemoSortOption = MemoSortOption.CREATED_TIME,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
) {
    val hasDateRange: Boolean
        get() = startDate != null || endDate != null

    val isActive: Boolean
        get() = sortOption != MemoSortOption.CREATED_TIME || hasDateRange
}
