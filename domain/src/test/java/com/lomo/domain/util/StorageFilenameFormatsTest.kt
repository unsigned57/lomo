/*
 * Test Contract:
 * - Unit under test: StorageFilenameFormatsTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for StorageFilenameFormatsTest.
 * - Boundary: boundary and edge cases for StorageFilenameFormatsTest.
 * - Failure: failure and error scenarios for StorageFilenameFormatsTest.
 * - Must-not-happen: invariants are never violated for StorageFilenameFormatsTest.
 *
 * - Behavior focus: test behavioral outcomes of StorageFilenameFormatsTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import java.time.LocalDate

class StorageFilenameFormatsTest : DomainFunSpec() {
    init {
        test("normalize returns default when pattern is null or unsupported") {
            StorageFilenameFormats.normalize(null) shouldBe StorageFilenameFormats.DEFAULT_PATTERN
            StorageFilenameFormats.normalize("yyyy/MM/dd") shouldBe StorageFilenameFormats.DEFAULT_PATTERN
        }

        test("normalize keeps supported pattern") {
            StorageFilenameFormats.normalize("yyyy-MM-dd") shouldBe "yyyy-MM-dd"
        }

        test("parseOrNull parses all supported formats") {
            val expected = LocalDate.of(2024, 2, 29)
            val samples =
                listOf(
                    "2024_02_29",
                    "2024-02-29",
                    "2024.02.29",
                    "20240229",
                    "02-29-2024",
                )

            samples.forEach { raw ->
                StorageFilenameFormats.parseOrNull(raw) shouldBe expected
            }
        }

        test("parseOrNull rejects unsupported and invalid dates") {
            StorageFilenameFormats.parseOrNull("2024/02/29") shouldBe null
            StorageFilenameFormats.parseOrNull("2023_02_29") shouldBe null
            StorageFilenameFormats.parseOrNull("02-30-2024") shouldBe null
        }

        test("format-then-parse round-trips for every supported pattern and any date in the supported range") {
            val patternArb = Arb.of(StorageFilenameFormats.supportedPatterns)
            val dateArb = Arb.localDate(minDate = LocalDate.of(1900, 1, 1), maxDate = LocalDate.of(9999, 12, 31))
            checkAll(patternArb, dateArb) { pattern, date ->
                val formatted = StorageFilenameFormats.formatter(pattern).format(date)
                StorageFilenameFormats.parseOrNull(formatted) shouldBe date
            }
        }
    }
}
