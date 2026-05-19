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


import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoIdentityPolicy
 * - Behavior focus: base-id construction, collision suffix/index resolution, timestamp offset clamping, and base/collision matching.
 * - Observable outcomes: generated ids, selected collision index, offset timestamps, and match Booleans.
 * - TDD proof: Fails before behavior changes or migration are applied.
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

        test("timestamp offset is clamped and base collision matching accepts only expected ids") {
            policy.applyTimestampOffset(1_000L, -5) shouldBe 1_000L
            policy.applyTimestampOffset(1_000L, 5_000) shouldBe 1_999L
            (policy.matchesBaseOrCollision("memo_1", "memo")) shouldBe true
            (policy.matchesBaseOrCollision("memo", "memo")) shouldBe true
            (policy.matchesBaseOrCollision("memoX_1", "memo")) shouldBe false
        }

        test("content hash trims whitespace before hashing") {
            MemoIdentityPolicy.contentHashHex("  hello  ") shouldBe MemoIdentityPolicy.contentHashHex("hello")
        }
    }
}
