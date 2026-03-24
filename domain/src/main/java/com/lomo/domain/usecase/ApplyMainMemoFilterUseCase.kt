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
            if (filter.sortAscending) {
                compareByDescending<Memo> { memo -> memo.isPinned }
                    .thenBy { memo -> sortTimestamp(memo, filter.sortOption) }
                    .thenBy { memo -> memo.timestamp }
                    .thenBy { memo -> memo.id }
            } else {
                compareByDescending<Memo> { memo -> memo.isPinned }
                    .thenByDescending { memo -> sortTimestamp(memo, filter.sortOption) }
                    .thenByDescending { memo -> memo.timestamp }
                    .thenByDescending { memo -> memo.id }
            }

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
        val memoDate = memo.localDate ?: memo.timestamp.toLocalDate()
        val afterStart = startDate == null || memoDate >= startDate
        val beforeEnd = endDate == null || memoDate <= endDate
        return afterStart && beforeEnd
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
