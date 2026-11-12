package com.lomo.domain.usecase

import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoQuerySpec
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/*
 * Behavior Contract:
 * Capability: open review date bounds; owner: domain query mapping; priority: P1.
 * Scenarios:
 * Given only a start/end date, when converted into a native-query spec, then the other bound stays open.
 * Production scope: apps/android/domain/src/model/MemoQuerySpec.kt
 * TDD proof: domain suite RED on open end/start assertions before canonical range fix; GREEN after.
 * Excludes: timezone conversion and Compose date entry.
 * Observable outcomes: query filter start/end bounds retain independent nullable values.
 */
class ReviewDateRangeTest : DomainFunSpec() {
    init {
        test("given only a start date when building a filter then the end remains open") {
            val date = LocalDate.of(2026, 1, 1)
            val range = MemoQuerySpec.fromFilter(filter = MemoListFilter(startDate = date)).dateRange
            range.startDate shouldBe date
            range.endDate shouldBe null
        }
        test("given only an end date when building a filter then the start remains open") {
            val date = LocalDate.of(2026, 1, 31)
            val range = MemoQuerySpec.fromFilter(filter = MemoListFilter(endDate = date)).dateRange
            range.startDate shouldBe null
            range.endDate shouldBe date
        }
    }
}
