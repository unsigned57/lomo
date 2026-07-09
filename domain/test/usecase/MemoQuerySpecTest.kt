/*
 * Behavior Contract:
 * - Unit under test: MemoQuerySpec
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: represent memo query text, content filters, and sort intent as one reusable domain contract.
 *
 * Scenarios:
 * - Given blank query text and no filters, when a spec is created, then it represents the all-memos query.
 * - Given content filters, when a memo analysis is evaluated, then all selected criteria must match.
 * - Given query text plus filters and sort, when a spec is inspected, then text, filter, and sort semantics compose.
 *
 * Observable outcomes:
 * - normalized query text, all-memos flag, content-analysis match result, and explicit sort contract.
 *
 * TDD proof:
 * - Fails before implementation because MemoQuerySpec and MemoFilterCriterion do not exist.
 *
 * Excludes:
 * - repository SQL translation, ViewModel state, result pagination, locale-specific tokenization.
 */
package com.lomo.domain.usecase

import com.lomo.domain.model.MemoContentAnalysis
import com.lomo.domain.model.MemoFilterCriterion
import com.lomo.domain.model.MemoQueryDateRange
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySort
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.model.MemoSortOption
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class MemoQuerySpecTest : FunSpec({
    test("given blank query and no filters when spec is created then it represents all memos") {
        val spec = MemoQuerySpec(queryText = "   ")

        spec.normalizedQueryText shouldBe ""
        spec.isAllMemosQuery shouldBe true
        spec.hasContentFilters shouldBe false
        spec.matches(MemoContentAnalysis.None) shouldBe true
    }

    test("given content filters when analysis is evaluated then all selected criteria must match") {
        val spec =
            MemoQuerySpec(
                criteria =
                    setOf(
                        MemoFilterCriterion.HasTodo,
                        MemoFilterCriterion.HasAttachment,
                        MemoFilterCriterion.HasUrl,
                    ),
            )

        spec.matches(
            MemoContentAnalysis(hasTodo = true, hasAttachment = true, hasUrl = true),
        ) shouldBe true
        spec.matches(
            MemoContentAnalysis(hasTodo = true, hasAttachment = true, hasUrl = false),
        ) shouldBe false
    }

    test("given query text filters and sort when spec is inspected then semantics compose") {
        val spec =
            MemoQuerySpec(
                queryText = "  invoice  ",
                criteria = setOf(MemoFilterCriterion.HasUrl),
                sort = MemoQuerySort(option = MemoSortOption.UPDATED_TIME, ascending = true),
            )

        spec.normalizedQueryText shouldBe "invoice"
        spec.isAllMemosQuery shouldBe false
        spec.hasContentFilters shouldBe true
        spec.sort shouldBe MemoQuerySort(option = MemoSortOption.UPDATED_TIME, ascending = true)
        spec.matches(MemoContentAnalysis(hasUrl = true)) shouldBe true
    }

    test("given legacy main filter when converted then nullable flags become explicit criteria") {
        val spec =
            MemoQuerySpec.fromFilter(
                queryText = "  receipt  ",
                filter =
                    MemoListFilter(
                        sortOption = MemoSortOption.UPDATED_TIME,
                        sortAscending = true,
                        startDate = LocalDate.of(2026, 1, 4),
                        endDate = LocalDate.of(2026, 1, 2),
                        hasTodo = true,
                        hasAttachment = false,
                        hasUrl = true,
                    ),
            )

        spec.normalizedQueryText shouldBe "receipt"
        spec.dateRange shouldBe
            MemoQueryDateRange(
                startDate = LocalDate.of(2026, 1, 2),
                endDate = LocalDate.of(2026, 1, 4),
            )
        spec.criteria shouldBe
            setOf(
                MemoFilterCriterion.HasTodo,
                MemoFilterCriterion.NoAttachment,
                MemoFilterCriterion.HasUrl,
            )
        spec.sort shouldBe MemoQuerySort(option = MemoSortOption.UPDATED_TIME, ascending = true)
        spec.matches(
            MemoContentAnalysis(hasTodo = true, hasAttachment = false, hasUrl = true),
        ) shouldBe true
    }

    test("given contradictory criteria when spec is created then invalid query state is rejected") {
        shouldThrow<IllegalArgumentException> {
            MemoQuerySpec(
                criteria = setOf(MemoFilterCriterion.HasTodo, MemoFilterCriterion.NoTodo),
            )
        }
    }
})
