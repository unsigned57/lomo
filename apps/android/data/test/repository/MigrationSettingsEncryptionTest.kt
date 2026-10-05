package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.usecase.MigrationEnvelopeException
import com.lomo.domain.usecase.MigrationPasswordException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Behavior Contract:
 * - Unit under test: MigrationSettingsEncryption.
 * - Owning layer: data (settings exchange format).
 * - Priority tier: P2.
 * - Capability: an external envelope cannot choose unbounded KDF work, malformed field
 *   lengths, or a format version this build did not produce; a legacy version-1 export
 *   still decrypts.
 *
 * Scenarios:
 * - Given a valid current envelope, when decrypted, then the plaintext round-trips.
 * - Given a valid legacy version-1 envelope (pre-AAD), when decrypted, then the plaintext
 *   round-trips.
 * - Given an oversized iteration count, when decrypted, then it is rejected before key
 *   derivation as a format failure.
 * - Given a malformed salt or nonce length, when decrypted, then it is rejected before
 *   derivation as a format failure.
 * - Given a wrong password, when decrypted, then it is rejected as a password failure.
 *
 * Observable outcomes: returned plaintext bytes, MigrationPasswordException (tag/key
 * failure), or MigrationEnvelopeException (format failure).
 *
 * TDD proof: before the budget check, `decryptSettings` derived the key with
 * `envelope.iterations`, so an `iterations = Int.MAX_VALUE` envelope requested unbounded
 * PBKDF2 work. This contract fails against that implementation and passes after the bounds
 * are enforced.
 *
 * Excludes: DataStore persistence, credential storage, and device-measured work factors.
 *
 * Test Change Justification:
 * - Reason category: envelope format versioning with AEAD-bound metadata.
 * - Old behavior/assertion being replaced: a single envelope shape whose header fields were not
 *   authenticated, and one failure type for wrong-password versus malformed envelopes.
 * - Why old assertion is no longer correct: unauthenticated envelope metadata let a tampered
 *   header steer decryption; current-version envelopes now bind metadata through AAD, while a
 *   legacy v1 export still decrypts without it.
 * - Coverage preserved by: round-trip, oversized-iteration and malformed-length scenarios remain,
 *   plus new arms for the v1 legacy envelope and the format/password failure split.
 * - Why this is not fitting the test to the implementation: assertions check observable
 *   decryption outcomes and exception types, not cipher internals.
 */

class MigrationSettingsEncryptionTest : DataFunSpec() {
    init {
        test("given a valid envelope when decrypted then the plaintext round-trips") {
            val plainText = "lomo settings payload".toByteArray()
            val envelope = encryptSettings(plainText, "correct horse")
            decryptSettings(envelope, "correct horse") shouldBe plainText
        }

        test("given a legacy version-1 envelope when decrypted then the plaintext round-trips") {
            // Version 1 is what production builds emitted before AAD metadata existed: PBKDF2
            // 120k, no updateAAD. Old exports must still restore.
            val plainText = "lomo settings payload".toByteArray()
            val legacy = legacyV1Envelope(password = "correct horse", plainText = plainText)
            decryptSettings(legacy, "correct horse") shouldBe plainText
        }

        test("given an oversized iteration count when decrypted then it is rejected before derivation") {
            val envelope = envelopeJson(iterations = Int.MAX_VALUE)
            shouldThrow<MigrationEnvelopeException> { decryptSettings(envelope, "password") }
        }

        test("given a malformed salt or nonce length when decrypted then it is rejected") {
            shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(iterations = 120_000, saltBase64 = "AAAA"), "password")
            }
            shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(iterations = 120_000, nonceBase64 = "AAAA"), "password")
            }
        }

        test("given a wrong password when decrypted then it is rejected as a password failure") {
            val envelope = encryptSettings("secret".toByteArray(), "right password")
            shouldThrow<MigrationPasswordException> { decryptSettings(envelope, "wrong password") }
        }
    }

    private fun envelopeJson(
        iterations: Int,
        saltBase64: String = "AAAAAAAAAAAAAAAAAAAAAA==",
        nonceBase64: String = "AAAAAAAAAAAAAAAA",
        cipherTextBase64: String = "AAAA",
    ): String =
        "{" +
            "\"version\":1," +
            "\"kdf\":\"PBKDF2WithHmacSHA256\"," +
            "\"cipher\":\"AES/GCM/NoPadding\"," +
            "\"iterations\":$iterations," +
            "\"saltBase64\":\"$saltBase64\"," +
            "\"nonceBase64\":\"$nonceBase64\"," +
            "\"cipherTextBase64\":\"$cipherTextBase64\"" +
            "}"

    /** Produces a byte-faithful version-1 envelope: PBKDF2 120k, GCM tag without AAD. */
    private fun legacyV1Envelope(
        password: String,
        plainText: ByteArray,
    ): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val key =
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val cipherText = cipher.doFinal(plainText)
        val encoder = Base64.getEncoder()
        return "{" +
            "\"version\":1," +
            "\"kdf\":\"PBKDF2WithHmacSHA256\"," +
            "\"cipher\":\"AES/GCM/NoPadding\"," +
            "\"iterations\":120000," +
            "\"saltBase64\":\"${encoder.encodeToString(salt)}\"," +
            "\"nonceBase64\":\"${encoder.encodeToString(nonce)}\"," +
            "\"cipherTextBase64\":\"${encoder.encodeToString(cipherText)}\"" +
            "}"
    }
}
