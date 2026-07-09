package com.lomo.domain.model

import java.time.LocalDate

enum class MemoSortOption {
    CREATED_TIME,
    UPDATED_TIME,
}

data class MemoListFilter(
    val sortOption: MemoSortOption = MemoSortOption.CREATED_TIME,
    val sortAscending: Boolean = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val hasTodo: Boolean? = null,
    val hasAttachment: Boolean? = null,
    val hasUrl: Boolean? = null,
) {
    val hasDateRange: Boolean
        get() = startDate != null || endDate != null

    val hasContentFlags: Boolean
        get() = hasTodo != null || hasAttachment != null || hasUrl != null

    val hasSortOverride: Boolean
        get() = sortOption != MemoSortOption.CREATED_TIME || sortAscending

    val isActive: Boolean
        get() = hasDateRange || hasContentFlags
}
