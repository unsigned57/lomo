/*
 * Behavior Contract:
 * - Unit under test: ShareCryptoUtilsTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ShareCryptoUtilsTest.
 * - Boundary: boundary and edge cases for ShareCryptoUtilsTest.
 * - Failure: failure and error scenarios for ShareCryptoUtilsTest.
 * - Must-not-happen: invariants are never violated for ShareCryptoUtilsTest.
 *
 * - Behavior focus: test behavioral outcomes of ShareCryptoUtilsTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - TDD proof: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.data.share

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



import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class ShareCryptoUtilsTest : DataFunSpec() {
    init {
        test("encryptText decryptText roundtrip succeeds") { `encryptText decryptText roundtrip succeeds`() }

        test("decryptText fails with wrong key or aad") { `decryptText fails with wrong key or aad`() }

        test("decryptBytes fails when ciphertext is tampered") { `decryptBytes fails when ciphertext is tampered`() }

        test("decodeNonceBase64 rejects malformed or wrong-length nonce") { `decodeNonceBase64 rejects malformed or wrong-length nonce`() }
    }


    private fun `encryptText decryptText roundtrip succeeds`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-1")!!
        val encrypted =
            ShareCryptoUtils.encryptText(
                keyHex = keyHex,
                plaintext = "hello lan share",
                aad = "memo-content",
            )

        val decrypted =
            ShareCryptoUtils.decryptText(
                keyHex = keyHex,
                ciphertextBase64 = encrypted.ciphertextBase64,
                nonceBase64 = encrypted.nonceBase64,
                aad = "memo-content",
            )

        decrypted shouldBe "hello lan share"
    }

    private fun `decryptText fails with wrong key or aad`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-2")!!
        val otherKeyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-3")!!
        val encrypted =
            ShareCryptoUtils.encryptText(
                keyHex = keyHex,
                plaintext = "secret",
                aad = "memo-content",
            )

        val wrongKeyResult =
            ShareCryptoUtils.decryptText(
                keyHex = otherKeyHex,
                ciphertextBase64 = encrypted.ciphertextBase64,
                nonceBase64 = encrypted.nonceBase64,
                aad = "memo-content",
            )
        val wrongAadResult =
            ShareCryptoUtils.decryptText(
                keyHex = keyHex,
                ciphertextBase64 = encrypted.ciphertextBase64,
                nonceBase64 = encrypted.nonceBase64,
                aad = "other-aad",
            )

        wrongKeyResult.shouldBeNull()
        wrongAadResult.shouldBeNull()
    }

    private fun `decryptBytes fails when ciphertext is tampered`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-crypto-4")!!
        val encrypted =
            ShareCryptoUtils.encryptBytes(
                keyHex = keyHex,
                plaintext = byteArrayOf(1, 2, 3, 4, 5),
                aad = "attachment:test.png",
            )
        encrypted.ciphertext.shouldNotBeNull()

        val tampered = encrypted.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        val tamperedResult =
            ShareCryptoUtils.decryptBytes(
                keyHex = keyHex,
                ciphertext = tampered,
                nonceBase64 = encrypted.nonceBase64,
                aad = "attachment:test.png",
            )
        val intactResult =
            ShareCryptoUtils.decryptBytes(
                keyHex = keyHex,
                ciphertext = encrypted.ciphertext,
                nonceBase64 = encrypted.nonceBase64,
                aad = "attachment:test.png",
            )

        tamperedResult.shouldBeNull()
        intactResult shouldBe byteArrayOf(1, 2, 3, 4, 5)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun `decodeNonceBase64 rejects malformed or wrong-length nonce`() {
        val wrongLength = Base64.Default.encode(byteArrayOf(1, 2, 3))

        ShareCryptoUtils.decodeNonceBase64("%%%").shouldBeNull()
        ShareCryptoUtils.decodeNonceBase64(wrongLength).shouldBeNull()
    }
}
