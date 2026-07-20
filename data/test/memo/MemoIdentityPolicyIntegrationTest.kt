package com.lomo.data.memo

/*
 * Behavior Contract:
 * - Unit under test: MemoIdentityPolicy + MemoSavePlanFactory integration.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: save-plan positional identity stays stable for first and collision ordinals.
 *
 * Scenarios:
 * - Given empty existing file, when save plan is built, then ordinal 0 identity is used.
 * - Given existing same-time blocks, when save plan is built, then ordinal advances.
 *
 * Observable outcomes: memo id and timestamp offset.
 * TDD proof: fails if save plan reintroduces content-hash identity.
 * Excludes: Rust document parse / file I/O.
 
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

import com.lomo.data.repository.MemoSavePlanFactory
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.fakeMarkdownWorkspaceContentProjector
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.ZoneId

class MemoIdentityPolicyIntegrationTest : DataFunSpec() {
    init {
        test("save plan uses ordinal zero identity for first occurrence") {
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

            savePlan.memo.id shouldBe memoIdentityPolicy.buildId("2026_02_01", "10:00", 0)
            savePlan.memo.timestamp shouldBe
                memoIdentityPolicy.applyTimestampOffset(timestamp, occurrenceIndex = 0)
        }

        test("save plan advances ordinal for collision occurrence") {
            val timestamp = dateTimeMillis(2026, 2, 1, 10, 0, 0)
            val fileContent =
                """
                - 10:00 Duplicate
                - 10:00 Duplicate
                """.trimIndent()

            val savePlanSecond =
                factory.create(
                    content = "Duplicate",
                    timestamp = timestamp,
                    filenameFormat = "yyyy_MM_dd",
                    timestampFormat = "HH:mm",
                    existingFileContent = fileContent,
                    precomputedSameTimestampCount = 1,
                )

            savePlanSecond.memo.id shouldBe memoIdentityPolicy.buildId("2026_02_01", "10:00", 1)
            savePlanSecond.memo.timestamp shouldBe
                memoIdentityPolicy.applyTimestampOffset(timestamp, occurrenceIndex = 1)
        }
    }

    private val memoIdentityPolicy = MemoIdentityPolicy()
    private val factory = MemoSavePlanFactory(fakeMarkdownWorkspaceContentProjector(), memoIdentityPolicy)

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
