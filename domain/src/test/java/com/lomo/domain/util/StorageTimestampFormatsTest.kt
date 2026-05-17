/*
 * Test Contract:
 * - Unit under test: StorageTimestampFormatsTest
 * - Owning layer: domain
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for StorageTimestampFormatsTest.
 * - Boundary: boundary and edge cases for StorageTimestampFormatsTest.
 * - Failure: failure and error scenarios for StorageTimestampFormatsTest.
 * - Must-not-happen: invariants are never violated for StorageTimestampFormatsTest.
 *
 * - Behavior focus: test behavioral outcomes of StorageTimestampFormatsTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StorageTimestampFormatsTest : DomainFunSpec() {
    init {
        test("parseMemoHeaderLine returns null for malformed or non-header input") {
            StorageTimestampFormats.parseMemoHeaderLine("") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("   ") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("09:30 content") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("-") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("-    ") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- content only") shouldBe null
        }
    }
    init {
        test("parseMemoHeaderLine returns null when timestamp fields are missing") {
            StorageTimestampFormats.parseMemoHeaderLine("- 09 content") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- :30 content") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 09: content") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 09::30 content") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 09:30: content") shouldBe null
        }
    }
    init {
        test("parseMemoHeaderLine returns null for illegal time values") {
            StorageTimestampFormats.parseMemoHeaderLine("- 24:01 overflow hour") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 09:60 overflow minute") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 09:10:60 overflow second") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 99:00 clearly invalid") shouldBe null
        }
    }
    init {
        test("parseMemoHeaderLine rejects non-whitespace token boundaries") {
            StorageTimestampFormats.parseMemoHeaderLine("- 09:30content") shouldBe null
            StorageTimestampFormats.parseMemoHeaderLine("- 9:30:05content") shouldBe null
        }
    }
    init {
        test("parseMemoHeaderLine parses supported time formats and content") {
            val withMinutes = StorageTimestampFormats.parseMemoHeaderLine("- 09:30 hello")
            withMinutes shouldNotBe null
            withMinutes?.timePart shouldBe "09:30"
            withMinutes?.contentPart shouldBe "hello"

            val singleDigitHour = StorageTimestampFormats.parseMemoHeaderLine("  - 9:30 hi")
            singleDigitHour shouldNotBe null
            singleDigitHour?.timePart shouldBe "9:30"
            singleDigitHour?.contentPart shouldBe "hi"

            val withSeconds = StorageTimestampFormats.parseMemoHeaderLine("- 9:30:05 details")
            withSeconds shouldNotBe null
            withSeconds?.timePart shouldBe "9:30:05"
            withSeconds?.contentPart shouldBe "details"

            val noContent = StorageTimestampFormats.parseMemoHeaderLine("- 09:30")
            noContent shouldNotBe null
            noContent?.timePart shouldBe "09:30"
            noContent?.contentPart shouldBe ""
        }
    }
}
