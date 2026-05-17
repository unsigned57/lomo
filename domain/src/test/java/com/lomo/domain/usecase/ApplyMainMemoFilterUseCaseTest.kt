/*
 * Test Contract:
 * - Unit under test: ApplyMainMemoFilterUseCase
 * - Behavior focus: sort and filter memo lists by date range, sort option, pinned priority.
 * - Observable outcomes: correct ordering, date range filtering, pinned-first sort.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: repository internals, UI rendering.
 */
package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId

class ApplyMainMemoFilterUseCaseTest : DomainFunSpec() {
    private val useCase = ApplyMainMemoFilterUseCase()
    init {
        test("default filter sorts by creation time descending") {
            val memos =
                listOf(
                    memo(id = "old", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0)),
                    memo(id = "new", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 5)),
                    memo(id = "middle", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 2)),
                )

            val result = useCase(memos, MemoListFilter())

            result.map { it.id } shouldBe listOf("new", "middle", "old")
        }
    }
    init {
        test("updated time sorting uses updatedAt") {
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

            result.map { it.id } shouldBe listOf("recently_updated", "new_but_not_updated")
        }
    }
    init {
        test("pinned memos are always ordered before non pinned memos") {
            val memos =
                listOf(
                    memo(id = "normal_new", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 12, 0)),
                    memo(
                        id = "pinned_old",
                        date = "2026_03_01",
                        timestamp = timestampOf(2026, 3, 1, 8, 0),
                        isPinned = true,
                    ),
                    memo(id = "normal_old", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 7, 0)),
                )

            val result = useCase(memos, MemoListFilter())

            result.map { it.id } shouldBe listOf("pinned_old", "normal_new", "normal_old")
        }
    }
    init {
        test("ascending sorting order is applied when enabled") {
            val memos =
                listOf(
                    memo(id = "new", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 5)),
                    memo(id = "old", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0)),
                    memo(id = "middle", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 2)),
                )

            val result =
                useCase(
                    memos = memos,
                    filter = MemoListFilter(sortAscending = true),
                )

            result.map { it.id } shouldBe listOf("old", "middle", "new")
        }
    }
    init {
        test("date range keeps only memos within selected boundaries") {
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

            result.map { it.id } shouldBe listOf("end", "start")
        }
    }
    init {
        test("date range accepts reversed boundaries") {
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

            result.map { it.id } shouldBe listOf("in")
        }
    }
    init {
        test("start date without end date keeps memos on and after selected day") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "before", date = "2026_02_28", timestamp = timestampOf(2026, 2, 28, 8, 0)),
                            memo(id = "start", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 8, 0)),
                            memo(id = "after", date = "2026_03_02", timestamp = timestampOf(2026, 3, 2, 8, 0)),
                        ),
                    filter = MemoListFilter(startDate = LocalDate.of(2026, 3, 1)),
                )

            result.map { it.id } shouldBe listOf("after", "start")
        }
    }
    init {
        test("end date without start date keeps memos on and before selected day") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "before", date = "2026_02_28", timestamp = timestampOf(2026, 2, 28, 8, 0)),
                            memo(id = "end", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 8, 0)),
                            memo(id = "after", date = "2026_03_02", timestamp = timestampOf(2026, 3, 2, 8, 0)),
                        ),
                    filter = MemoListFilter(endDate = LocalDate.of(2026, 3, 1)),
                )

            result.map { it.id } shouldBe listOf("end", "before")
        }
    }
    init {
        test("filter active flag reflects non default settings") {
            (MemoListFilter().isActive) shouldBe false
            (MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME).isActive) shouldBe true
            (MemoListFilter(sortAscending = true).isActive) shouldBe true
            (MemoListFilter(startDate = LocalDate.of(2026, 3, 1)).isActive) shouldBe true
        }
    }

    private fun memo(
        id: String,
        date: String,
        timestamp: Long,
        updatedAt: Long = timestamp,
        isPinned: Boolean = false,
    ): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            updatedAt = updatedAt,
            content = id,
            rawContent = id,
            dateKey = date,
            isPinned = isPinned,
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
