package com.lomo.data.memo


import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Test Contract:
 * - Unit under test: MemoContentHashPolicy
 * - Behavior focus: SHA-256 hash output shape, determinism, and whitespace trimming.
 * - Observable outcomes: 64-character lowercase hex string; same input → same hash; different input → different hash.
 * - Red phase: Fails before the fix because String.hashCode() produces a short hex of variable length and is
 *   not guaranteed to be consistent across JVM restarts.
 * - Excludes: MemoTextProcessor logic and file-system side effects.
 */
class MemoContentHashPolicyTest : DataFunSpec() {
    init {
        test("hashHex produces 64-character lowercase hex string (SHA-256 output shape)") { `hashHex produces 64-character lowercase hex string (SHA-256 output shape)`() }

        test("hashHex is deterministic for the same input") { `hashHex is deterministic for the same input`() }

        test("hashHex produces different values for different inputs") { `hashHex produces different values for different inputs`() }

        test("hashHex trims surrounding whitespace before hashing") { `hashHex trims surrounding whitespace before hashing`() }
    }


    private fun `hashHex produces 64-character lowercase hex string (SHA-256 output shape)`() {
        val hash = MemoContentHashPolicy.hashHex("hello")

        hash.length shouldBe 64
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Expected only lowercase hex characters but got: $hash"
        }
    }

    private fun `hashHex is deterministic for the same input`() {
        val first = MemoContentHashPolicy.hashHex("consistent content")
        val second = MemoContentHashPolicy.hashHex("consistent content")

        second shouldBe first
    }

    private fun `hashHex produces different values for different inputs`() {
        val hash1 = MemoContentHashPolicy.hashHex("content A")
        val hash2 = MemoContentHashPolicy.hashHex("content B")

        hash2 shouldNotBe hash1
    }

    private fun `hashHex trims surrounding whitespace before hashing`() {
        val plain = MemoContentHashPolicy.hashHex("note")
        val padded = MemoContentHashPolicy.hashHex("  note  ")

        padded shouldBe plain
    }
}
