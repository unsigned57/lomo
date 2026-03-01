package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ApplyMainMemoFilterUseCase {
    operator fun invoke(
        memos: List<Memo>,
        filter: MemoListFilter,
    ): List<Memo> {
        val normalizedStartDate = minOfOrNull(filter.startDate, filter.endDate)
        val normalizedEndDate = maxOfOrNull(filter.startDate, filter.endDate)
        val comparator =
            compareByDescending<Memo> { memo -> sortTimestamp(memo, filter.sortOption) }
                .thenByDescending { memo -> memo.timestamp }
                .thenByDescending { memo -> memo.id }

        return memos
            .asSequence()
            .filter { memo ->
                isInDateRange(
                    memo = memo,
                    startDate = normalizedStartDate,
                    endDate = normalizedEndDate,
                )
            }.sortedWith(comparator)
            .toList()
    }

    private fun minOfOrNull(
        first: LocalDate?,
        second: LocalDate?,
    ): LocalDate? =
        when {
            first == null -> second
            second == null -> first
            first <= second -> first
            else -> second
        }

    private fun maxOfOrNull(
        first: LocalDate?,
        second: LocalDate?,
    ): LocalDate? =
        when {
            first == null -> second
            second == null -> first
            first >= second -> first
            else -> second
        }

    private fun isInDateRange(
        memo: Memo,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Boolean {
        if (startDate == null && endDate == null) return true
        val memoDate = memo.localDate ?: memo.timestamp.toLocalDate()
        if (startDate != null && memoDate < startDate) return false
        if (endDate != null && memoDate > endDate) return false
        return true
    }

    private fun Long.toLocalDate(): LocalDate =
        Instant
            .ofEpochMilli(this)
            .atZone(DEFAULT_ZONE)
            .toLocalDate()

    private fun sortTimestamp(
        memo: Memo,
        sortOption: MemoSortOption,
    ): Long =
        when (sortOption) {
            MemoSortOption.CREATED_TIME -> memo.timestamp
            MemoSortOption.UPDATED_TIME -> memo.updatedAt
        }

    private companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.systemDefault()
    }
}
