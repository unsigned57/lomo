/*
 * Behavior Contract:
 * - Unit under test: ApplyMainMemoFilterUseCase
 * - Behavior focus: sort and filter memo lists by date range, sort option, pinned priority, and content flags (todo/attachment/url).
 * - Observable outcomes: correct ordering, date range filtering, pinned-first sort, three-state content predicate.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository internals, UI rendering.
 */
package com.lomo.domain.usecase

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId

class ApplyMainMemoFilterUseCaseTest : DomainFunSpec() {
    init {
        val useCase = ApplyMainMemoFilterUseCase()

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

        test("filter active flag reflects non default settings") {
            (MemoListFilter().isActive) shouldBe false
            (MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME).isActive) shouldBe false
            (MemoListFilter(sortAscending = true).isActive) shouldBe false
            (MemoListFilter(startDate = LocalDate.of(2026, 3, 1)).isActive) shouldBe true
            (MemoListFilter(hasTodo = true).isActive) shouldBe true
            (MemoListFilter(hasAttachment = false).isActive) shouldBe true
            (MemoListFilter(hasUrl = true).isActive) shouldBe true
        }

        test("hasSortOverride reflects non default sort settings") {
            (MemoListFilter().hasSortOverride) shouldBe false
            (MemoListFilter(sortOption = MemoSortOption.UPDATED_TIME).hasSortOverride) shouldBe true
            (MemoListFilter(sortAscending = true).hasSortOverride) shouldBe true
            (MemoListFilter(startDate = LocalDate.of(2026, 3, 1)).hasSortOverride) shouldBe false
            (MemoListFilter(hasTodo = true).hasSortOverride) shouldBe false
        }

        test("hasTodo true keeps only memos whose content contains a todo box") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "plain", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 9, 0), content = "plain text"),
                            memo(id = "todo", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0), content = "- [ ] buy milk"),
                        ),
                    filter = MemoListFilter(hasTodo = true),
                )

            result.map { it.id } shouldBe listOf("todo")
        }

        test("hasTodo false excludes memos that contain a todo box") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "plain", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 9, 0), content = "plain text"),
                            memo(id = "todo", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0), content = "- [x] done"),
                        ),
                    filter = MemoListFilter(hasTodo = false),
                )

            result.map { it.id } shouldBe listOf("plain")
        }

        test("hasAttachment true keeps memos with image or audio references") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "plain", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 9, 0), content = "no media"),
                            memo(id = "img", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0), content = "![](a.png)"),
                            memo(id = "voice", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 11, 0), content = "[v](r/v.m4a)"),
                        ),
                    filter = MemoListFilter(hasAttachment = true),
                )

            result.map { it.id } shouldBe listOf("voice", "img")
        }

        test("hasUrl true keeps memos containing an http link") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "plain", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 9, 0), content = "no link"),
                            memo(id = "link", date = "2026_03_01", timestamp = timestampOf(2026, 3, 1, 10, 0), content = "see https://example.com"),
                        ),
                    filter = MemoListFilter(hasUrl = true),
                )

            result.map { it.id } shouldBe listOf("link")
        }

        test("content flags AND with date range") {
            val result =
                useCase(
                    memos =
                        listOf(
                            memo(id = "old_todo", date = "2026_02_27", timestamp = timestampOf(2026, 2, 27, 9, 0), content = "- [ ] old"),
                            memo(id = "new_plain", date = "2026_03_02", timestamp = timestampOf(2026, 3, 2, 9, 0), content = "plain"),
                            memo(id = "new_todo", date = "2026_03_02", timestamp = timestampOf(2026, 3, 2, 10, 0), content = "- [ ] new"),
                        ),
                    filter =
                        MemoListFilter(
                            startDate = LocalDate.of(2026, 3, 1),
                            hasTodo = true,
                        ),
                )

            result.map { it.id } shouldBe listOf("new_todo")
        }
    }

    private fun memo(
        id: String,
        date: String,
        timestamp: Long,
        updatedAt: Long = timestamp,
        isPinned: Boolean = false,
        content: String = id,
    ): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            updatedAt = updatedAt,
            content = content,
            rawContent = content,
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
