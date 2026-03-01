package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ApplyMainMemoFilterUseCaseTest {
    private val useCase = ApplyMainMemoFilterUseCase()

    @Test
    fun `default filter sorts by creation time descending`() {
        val memos =
            listOf(
                memo(id = "old", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0)),
                memo(id = "new", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 5)),
                memo(id = "middle", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 2)),
            )

        val result = useCase(memos, MemoListFilter())

        assertEquals(listOf("new", "middle", "old"), result.map { it.id })
    }

    @Test
    fun `updated time sorting uses updatedAt`() {
        val memos =
            listOf(
                memo(
                    id = "recently_updated",
                    date = "2026_03_01",
                    timestamp = timestampOf(2026, 3, 1, 9, 0),
                    updatedAt = timestampOf(2026, 3, 1, 12, 0),
                ),
                memo(
                    id = "new_but_not_updated",
                    date = "2026_03_01",
                    timestamp = timestampOf(2026, 3, 1, 11, 30),
                    updatedAt = timestampOf(2026, 3, 1, 11, 30),
                ),
            )

        val result =
            useCase(
                memos = memos,
                filter = MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME),
            )

        assertEquals(listOf("recently_updated", "new_but_not_updated"), result.map { it.id })
    }

    @Test
    fun `date range keeps only memos within selected boundaries`() {
        val result =
            useCase(
                memos =
                    listOf(
                        memo(id = "before", date = "2026_02_27", timestamp = timestampOf(2026, 2, 27, 8, 0)),
                        memo(id = "start", date = "2026_02_28", timestamp = timestampOf(2026, 2, 28, 8, 0)),
                        memo(id = "end", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 8, 0)),
                        memo(id = "after", date = "2026_03_02", timestamp = timestampOf(2026, 3, 2, 8, 0)),
                    ),
                filter =
                    MemoListFilter(
                        startDate = LocalDate.of(2026, 2, 28),
                        endDate = LocalDate.of(2026, 3, 1),
                    ),
            )

        assertEquals(listOf("end", "start"), result.map { it.id })
    }

    @Test
    fun `date range accepts reversed boundaries`() {
        val result =
            useCase(
                memos =
                    listOf(
                        memo(id = "in", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 8, 0)),
                        memo(id = "out", date = "2026_03_04", timestamp = timestampOf(2026, 3, 4, 8, 0)),
                    ),
                filter =
                    MemoListFilter(
                        startDate = LocalDate.of(2026, 3, 3),
                        endDate = LocalDate.of(2026, 2, 28),
                    ),
            )

        assertEquals(listOf("in"), result.map { it.id })
    }

    @Test
    fun `filter active flag reflects non default settings`() {
        assertFalse(MemoListFilter().isActive)
        assertTrue(MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME).isActive)
        assertTrue(MemoListFilter(startDate = LocalDate.of(2026, 3, 1)).isActive)
    }

    private fun memo(
        id: String,
        date: String,
        timestamp: Long,
        updatedAt: Long = timestamp,
    ): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            updatedAt = updatedAt,
            content = id,
            rawContent = id,
            dateKey = date,
        )

    private fun timestampOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        LocalDate
            .of(year, month, day)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
