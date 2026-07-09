/*
 * Behavior Contract:
 * - Unit under test: MemoIdentityPolicyIntegrationTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for MemoIdentityPolicyIntegrationTest.
 * - Boundary: boundary and edge cases for MemoIdentityPolicyIntegrationTest.
 * - Failure: failure and error scenarios for MemoIdentityPolicyIntegrationTest.
 * - Must-not-happen: invariants are never violated for MemoIdentityPolicyIntegrationTest.
 *
 * - Behavior focus: test behavioral outcomes of MemoIdentityPolicyIntegrationTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.data.memo

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



import com.lomo.data.parser.MarkdownParser
import com.lomo.data.repository.MemoSavePlanFactory
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.usecase.MemoIdentityPolicy
import java.time.LocalDateTime
import java.time.ZoneId
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

class MemoIdentityPolicyIntegrationTest : DataFunSpec() {
    init {
        test("save plan and parser share same identity for first occurrence") { `save plan and parser share same identity for first occurrence`() }

        test("save plan and parser share same identity for collision occurrence") { `save plan and parser share same identity for collision occurrence`() }
    }


    private val textProcessor = MemoTextProcessor()
    private val memoIdentityPolicy = MemoIdentityPolicy()
    private val parser = MarkdownParser(textProcessor, memoIdentityPolicy)
    private val factory = MemoSavePlanFactory(parser, textProcessor, memoIdentityPolicy)

    private fun `save plan and parser share same identity for first occurrence`() {
        val timestamp = dateTimeMillis(2026, 2, 1, 10, 0, 0)
        val savePlan =
            factory.create(
                content = "Buy milk",
                timestamp = timestamp,
                filenameFormat = "yyyy_MM_dd",
                timestampFormat = "HH:mm",
                existingFileContent = "",
                precomputedSameTimestampCount = 0,
            )

        val parsed =
            parser
                .parseContent(
                    content = savePlan.rawContent,
                    filename = savePlan.dateKey,
                    fallbackTimestampMillis = timestamp,
                ).single()

        parsed.id shouldBe savePlan.memo.id
        parsed.timestamp shouldBe savePlan.memo.timestamp
    }

    private fun `save plan and parser share same identity for collision occurrence`() {
        val timestamp = dateTimeMillis(2026, 2, 1, 10, 0, 0)
        val fileContent =
            """
            - 10:00 Duplicate
            - 10:00 Duplicate
            """.trimIndent()
        val parsedSecond =
            parser.parseContent(
                content = fileContent,
                filename = "2026_02_01",
                fallbackTimestampMillis = timestamp,
            )[1]

        val savePlanSecond =
            factory.create(
                content = "Duplicate",
                timestamp = timestamp,
                filenameFormat = "yyyy_MM_dd",
                timestampFormat = "HH:mm",
                existingFileContent = fileContent,
                precomputedSameTimestampCount = 1,
            )

        savePlanSecond.memo.id shouldBe parsedSecond.id
        savePlanSecond.memo.timestamp shouldBe parsedSecond.timestamp
    }

    private fun dateTimeMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long =
        LocalDateTime
            .of(year, month, day, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
