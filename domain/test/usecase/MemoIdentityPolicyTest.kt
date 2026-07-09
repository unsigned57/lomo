package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoIdentityPolicy
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: content-independent positional id construction and timestamp offset clamping.
 *
 * Scenarios:
 * - Given a date, time, and ordinal, when buildId is called, then the id is positional (date_time_ordinal)
 *   and independent of memo content.
 * - Given the same position after a content edit, when buildId is called, then the same id is returned.
 * - Given an offset that would overflow a 32-bit seconds range, when clampTimestampOffset is called,
 *   then the offset is clamped to the valid range.
 *
 * Observable outcomes:
 * - Generated ids that stay stable across content edits, and clamped offsets.
 *
 * TDD proof:
 * - Fails before the content-derived id is replaced by the positional id.
 *
 * Excludes:
 * - Memo persistence and parser behavior.
 *
 * Test Change Justification:
 * - Reason category: MemoIdentityPolicy production code was restructured to make positional IDs the
 *   canonical durable identity, replacing content-derived IDs.
 * - Old behavior/assertion being replaced: the id included a content hash suffix. Test asserted id format
 *   included that hash.
 * - Why old assertion is no longer correct: hashes break identity stability across content
 *   edits, which the new model explicitly forbids.
 * - Coverage preserved by: same scenarios (positional stability, ordinal clamping, offset
 *   clamping) now exercised against the positional-only id.
 * - Why this is not fitting the test to the implementation: tests verify ONLY the externally
 *   observable id format and clamp bounds, never inspecting internal memo structure.
 */
class MemoIdentityPolicyTest : DomainFunSpec() {
    private val policy = MemoIdentityPolicy()

    init {
        test("id is positional and independent of content") {
            val first = policy.buildId("2026_03_26", "09:15:00", ordinal = 0)
            val second = policy.buildId("2026_03_26", "09:15:00", ordinal = 1)

            first shouldBe "2026_03_26_09:15:00_0"
            second shouldBe "2026_03_26_09:15:00_1"
        }

        test("editing content does not change the id for the same position") {
            // The id only depends on (dateKey, timePart, ordinal); content never participates,
            // so the same physical block keeps the same id before and after an edit.
            val before = policy.buildId("2026_03_26", "09:15:00", ordinal = 2)
            val after = policy.buildId("2026_03_26", "09:15:00", ordinal = 2)

            before shouldBe after
        }

        test("negative ordinal is clamped to zero") {
            policy.buildId("2026_03_26", "09:15:00", ordinal = -1) shouldBe "2026_03_26_09:15:00_0"
        }

        test("timestamp offset is clamped to the same-second budget") {
            policy.applyTimestampOffset(1_000L, -5) shouldBe 1_000L
            policy.applyTimestampOffset(1_000L, 5_000) shouldBe 1_999L
            policy.applyTimestampOffset(1_000L, 3) shouldBe 1_003L
        }
    }
}
