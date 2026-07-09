/*
 * Behavior Contract:
 * - Unit under test: ShareAuthUtilsTest
 * - Owning layer: data
 * - Priority tier: P0
 *
 * Scenarios:
 * - Happy: standard happy path for ShareAuthUtilsTest.
 * - Boundary: boundary and edge cases for ShareAuthUtilsTest.
 * - Failure: failure and error scenarios for ShareAuthUtilsTest.
 * - Must-not-happen: invariants are never violated for ShareAuthUtilsTest.
 *
 * - Behavior focus: test behavioral outcomes of ShareAuthUtilsTest.
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



import java.security.MessageDigest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class ShareAuthUtilsTest : DataFunSpec() {
    init {
        test("deriveKeyMaterialFromPairingCode returns stable versioned material") { `deriveKeyMaterialFromPairingCode returns stable versioned material`() }

        test("deriveKeyMaterialFromPairingCode rejects invalid lengths") { `deriveKeyMaterialFromPairingCode rejects invalid lengths`() }

        test("resolveKeySet supports v2 and legacy formats") { `resolveKeySet supports v2 and legacy formats`() }

        test("verifySignature succeeds for matching payload and fails for tampered payload") { `verifySignature succeeds for matching payload and fails for tampered payload`() }

        test("payload canonicalization ignores attachment order") { `payload canonicalization ignores attachment order`() }

        test("timestamp window check behaves as expected") { `timestamp window check behaves as expected`() }
    }


    private fun `deriveKeyMaterialFromPairingCode returns stable versioned material`() {
        val material1 = ShareAuthUtils.deriveKeyMaterialFromPairingCode("shared-secret-123")
        val material2 = ShareAuthUtils.deriveKeyMaterialFromPairingCode("shared-secret-123")

        material1.shouldNotBeNull()
        material2 shouldBe material1
        (material1.startsWith("v2:")).shouldBeTrue()

        val keySet = ShareAuthUtils.resolveKeySet(material1)
        keySet.shouldNotBeNull()
        (keySet.primaryKeyHex.matches(Regex("^[0-9a-f]{64}$"))).shouldBeTrue()
        keySet.candidateKeyHexes.size shouldBe 2
        (keySet.candidateKeyHexes.all { it.matches(Regex("^[0-9a-f]{64}$")) }).shouldBeTrue()
    }

    private fun `deriveKeyMaterialFromPairingCode rejects invalid lengths`() {
        ShareAuthUtils.deriveKeyMaterialFromPairingCode("short").shouldBeNull()
        ShareAuthUtils.deriveKeyMaterialFromPairingCode("x".repeat(65)).shouldBeNull()
    }

    private fun `resolveKeySet supports v2 and legacy formats`() {
        val pairingCode = "pairing-code-legacy-compat"
        val material = ShareAuthUtils.deriveKeyMaterialFromPairingCode(pairingCode)
        val set = ShareAuthUtils.resolveKeySet(material)
        set.shouldNotBeNull()
        val expectedLegacy = legacyKey(pairingCode)
        (set.candidateKeyHexes.contains(expectedLegacy)).shouldBeTrue()

        val legacyOnly = legacyKey("legacy-only")
        val legacySet = ShareAuthUtils.resolveKeySet(legacyOnly)
        legacySet.shouldNotBeNull()
        legacySet.primaryKeyHex shouldBe legacyOnly
        legacySet.candidateKeyHexes shouldBe listOf(legacyOnly)
    }

    private fun `verifySignature succeeds for matching payload and fails for tampered payload`() {
        val keyHex = ShareAuthUtils.deriveKeyHexFromPairingCode("pairing-code-001")!!
        val payload =
            ShareAuthUtils.buildPreparePayloadToSign(
                senderName = "Pixel",
                encryptedContent = "ciphertextA==",
                contentNonce = "nonceA==",
                timestamp = 1234L,
                attachmentNames = listOf("b.png", "a.png"),
                authTimestampMs = 5000L,
                authNonce = "aabbccddeeff0011",
            )
        val signature = ShareAuthUtils.signPayloadHex(keyHex, payload)

        (ShareAuthUtils.verifySignature(keyHex, payload, signature)).shouldBeTrue()
        (ShareAuthUtils.verifySignature(keyHex, "$payload-tampered", signature)).shouldBeFalse()
    }

    private fun `payload canonicalization ignores attachment order`() {
        val payloadA =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = "token",
                encryptedContent = "ciphertextA==",
                contentNonce = "nonceA==",
                timestamp = 10L,
                attachmentNames = listOf("b.png", "a.png"),
                authTimestampMs = 20L,
                authNonce = "1122334455667788",
            )
        val payloadB =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = "token",
                encryptedContent = "ciphertextA==",
                contentNonce = "nonceA==",
                timestamp = 10L,
                attachmentNames = listOf("a.png", "b.png"),
                authTimestampMs = 20L,
                authNonce = "1122334455667788",
            )

        payloadB shouldBe payloadA
    }

    private fun `timestamp window check behaves as expected`() {
        (ShareAuthUtils.isTimestampWithinWindow(1_000L, nowMs = 1_500L, windowMs = 1_000L)).shouldBeTrue()
        (ShareAuthUtils.isTimestampWithinWindow(1_000L, nowMs = 2_500L, windowMs = 1_000L)).shouldBeFalse()
    }

    private fun legacyKey(pairingCode: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("lomo-lan-share-v1:${pairingCode.trim()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
