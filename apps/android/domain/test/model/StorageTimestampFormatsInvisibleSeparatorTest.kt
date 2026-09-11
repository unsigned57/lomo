package com.lomo.domain.model

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


import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: StorageTimestampFormats
 * - Behavior focus: memo header parsing when invisible separators appear before the leading dash or between the dash and timestamp token.
 * - Observable outcomes: parsed header presence, parsed time token, and stripped content remainder.
 * - TDD proof: Fails before the fix when a UTF-8 BOM or zero-width space prevents `- HH:mm:ss` memo headers from being recognized.
 * - Excludes: file loading, markdown rendering, and memo repository orchestration.
 */
class StorageTimestampFormatsInvisibleSeparatorTest : DomainFunSpec() {
    init {
        test("parseMemoHeaderLine ignores utf8 bom before storage header") {
            val parsed = StorageTimestampFormats.parseMemoHeaderLine("﻿- 17:56:16  正文")

            parsed shouldNotBe null
            parsed?.timePart shouldBe "17:56:16"
            parsed?.contentPart shouldBe "正文"
        }

        test("parseMemoHeaderLine ignores zero width space between dash and timestamp") {
            val parsed = StorageTimestampFormats.parseMemoHeaderLine("- ​17:56:16  正文")

            parsed shouldNotBe null
            parsed?.timePart shouldBe "17:56:16"
            parsed?.contentPart shouldBe "正文"
        }
    }
}
