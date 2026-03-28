package com.lomo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoIdentityPolicy
 * - Behavior focus: base-id construction, collision suffix/index resolution, timestamp offset clamping, and base/collision matching.
 * - Observable outcomes: generated ids, selected collision index, offset timestamps, and match Booleans.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: memo persistence and parser behavior.
 */
class MemoIdentityPolicyTest {
    private val policy = MemoIdentityPolicy()

    @Test
    fun `buildBaseId and collision helpers keep deterministic id format`() {
        val baseId = policy.buildBaseId("2026_03_26", "0915", "  hello world  ")

        assertTrue(baseId.startsWith("2026_03_26_0915_"))
        assertEquals(baseId, policy.applyCollisionSuffix(baseId, 0))
        assertEquals("${baseId}_2", policy.applyCollisionSuffix(baseId, 2))
        assertEquals(
            2,
            policy.nextCollisionIndex(
                existingIds = setOf(baseId, "${baseId}_1"),
                baseId = baseId,
            ),
        )
    }

    @Test
    fun `timestamp offset is clamped and base collision matching accepts only expected ids`() {
        assertEquals(1_000L, policy.applyTimestampOffset(1_000L, -5))
        assertEquals(1_999L, policy.applyTimestampOffset(1_000L, 5_000))
        assertTrue(policy.matchesBaseOrCollision("memo_1", "memo"))
        assertTrue(policy.matchesBaseOrCollision("memo", "memo"))
        assertFalse(policy.matchesBaseOrCollision("memoX_1", "memo"))
    }

    @Test
    fun `content hash trims whitespace before hashing`() {
        assertEquals(
            MemoIdentityPolicy.contentHashHex("hello"),
            MemoIdentityPolicy.contentHashHex("  hello  "),
        )
    }
}
