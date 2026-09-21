package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.usecase.MigrationPasswordException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MigrationSettingsEncryption.
 * - Owning layer: data (settings exchange format).
 * - Priority tier: P2.
 * - Capability: an external envelope cannot choose unbounded KDF work or malformed field lengths.
 *
 * Scenarios:
 * - Given a valid envelope, when decrypted, then the plaintext round-trips.
 * - Given an oversized iteration count, when decrypted, then it is rejected before key derivation.
 * - Given a malformed salt or nonce length, when decrypted, then it is rejected before derivation.
 * - Given a wrong password, when decrypted, then it is rejected as a password failure.
 *
 * Observable outcomes: returned plaintext bytes, or a MigrationPasswordException.
 *
 * TDD proof: before the budget check, `decryptSettings` derived the key with `envelope.iterations`,
 * so an `iterations = Int.MAX_VALUE` envelope requested unbounded PBKDF2 work. This contract fails
 * against that implementation and passes after the bounds are enforced.
 *
 * Excludes: DataStore persistence, credential storage, and device-measured work factors.
 */

class MigrationSettingsEncryptionTest : DataFunSpec() {
    init {
        test("given a valid envelope when decrypted then the plaintext round-trips") {
            val plainText = "lomo settings payload".toByteArray()
            val envelope = encryptSettings(plainText, "correct horse")
            decryptSettings(envelope, "correct horse") shouldBe plainText
        }

        test("given an oversized iteration count when decrypted then it is rejected before derivation") {
            val envelope = envelopeJson(iterations = Int.MAX_VALUE)
            shouldThrow<MigrationPasswordException> { decryptSettings(envelope, "password") }
        }

        test("given a malformed salt or nonce length when decrypted then it is rejected") {
            shouldThrow<MigrationPasswordException> {
                decryptSettings(envelopeJson(iterations = 120_000, saltBase64 = "AAAA"), "password")
            }
            shouldThrow<MigrationPasswordException> {
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
}
