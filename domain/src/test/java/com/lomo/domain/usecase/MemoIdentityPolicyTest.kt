package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoIdentityPolicy
 * - Behavior focus: base-id construction, collision suffix/index resolution, timestamp offset clamping, and base/collision matching.
 * - Observable outcomes: generated ids, selected collision index, offset timestamps, and match Booleans.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: memo persistence and parser behavior.
 */
class MemoIdentityPolicyTest : DomainFunSpec() {
    private val policy = MemoIdentityPolicy()
    init {
        test("buildBaseId and collision helpers keep deterministic id format") {
            val baseId = policy.buildBaseId("2026_03_26", "0915", "  hello world  ")

            (baseId.startsWith("2026_03_26_0915_")) shouldBe true
            policy.applyCollisionSuffix(baseId, 0) shouldBe baseId
            policy.applyCollisionSuffix(baseId, 2) shouldBe "${baseId}_2"
            policy.nextCollisionIndex(
                    existingIds = setOf(baseId, "${baseId}_1"),
                    baseId = baseId,
                ) shouldBe 2
        }
    }
    init {
        test("timestamp offset is clamped and base collision matching accepts only expected ids") {
            policy.applyTimestampOffset(1_000L, -5) shouldBe 1_000L
            policy.applyTimestampOffset(1_000L, 5_000) shouldBe 1_999L
            (policy.matchesBaseOrCollision("memo_1", "memo")) shouldBe true
            (policy.matchesBaseOrCollision("memo", "memo")) shouldBe true
            (policy.matchesBaseOrCollision("memoX_1", "memo")) shouldBe false
        }
    }
    init {
        test("content hash trims whitespace before hashing") {
            MemoIdentityPolicy.contentHashHex("  hello  ") shouldBe MemoIdentityPolicy.contentHashHex("hello")
        }
    }
}
