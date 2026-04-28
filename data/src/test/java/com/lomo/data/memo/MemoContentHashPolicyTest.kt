package com.lomo.data.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoContentHashPolicy
 * - Behavior focus: SHA-256 hash output shape, determinism, and whitespace trimming.
 * - Observable outcomes: 64-character lowercase hex string; same input → same hash; different input → different hash.
 * - Red phase: Fails before the fix because String.hashCode() produces a short hex of variable length and is
 *   not guaranteed to be consistent across JVM restarts.
 * - Excludes: MemoTextProcessor logic and file-system side effects.
 */
class MemoContentHashPolicyTest {
    @Test
    fun `hashHex produces 64-character lowercase hex string (SHA-256 output shape)`() {
        val hash = MemoContentHashPolicy.hashHex("hello")

        assertEquals(64, hash.length)
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Expected only lowercase hex characters but got: $hash"
        }
    }

    @Test
    fun `hashHex is deterministic for the same input`() {
        val first = MemoContentHashPolicy.hashHex("consistent content")
        val second = MemoContentHashPolicy.hashHex("consistent content")

        assertEquals(first, second)
    }

    @Test
    fun `hashHex produces different values for different inputs`() {
        val hash1 = MemoContentHashPolicy.hashHex("content A")
        val hash2 = MemoContentHashPolicy.hashHex("content B")

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashHex trims surrounding whitespace before hashing`() {
        val plain = MemoContentHashPolicy.hashHex("note")
        val padded = MemoContentHashPolicy.hashHex("  note  ")

        assertEquals(plain, padded)
    }
}
