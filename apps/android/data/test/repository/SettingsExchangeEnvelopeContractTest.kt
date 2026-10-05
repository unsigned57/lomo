package com.lomo.data.repository

// adversarial-audit: settings envelope metadata must be AEAD-authenticated (updateAAD),
// the wire payload must carry an enforced settingsSchemaVersion, and wrong-password /
// corrupted / unsupported-version inputs must not collapse into one exception type.

import com.lomo.domain.usecase.MigrationEnvelopeException
import com.lomo.domain.usecase.MigrationPasswordException
import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Behavior Contract:
 * - Unit under test: settings exchange envelope decoding/decryption.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: envelope metadata is AEAD-authenticated for the current version, the wire payload
 *   carries an enforced settingsSchemaVersion, and wrong-password, corrupted and
 *   unsupported-version inputs surface as distinct failures.
 *
 * Scenarios:
 * - Given envelope metadata outside the pinned profile, when decoded, then it is rejected before
 *   key derivation.
 * - Given a version-2 envelope whose ciphertext was forged without AAD, when decrypted, then the
 *   GCM tag fails.
 * - Given a corrupted or unsupported envelope, when decoded, then it fails as
 *   MigrationEnvelopeException, not MigrationPasswordException.
 * - Given a wire payload with an unknown settingsSchemaVersion, when decoded, then it is refused.
 * - Given an oversized envelope text, when decoded, then it is rejected before parsing.
 *
 * Observable outcomes: MigrationPasswordException versus MigrationEnvelopeException, manifest
 * schemaVersion, tag verification failures.
 *
 * TDD proof:
 * - The AAD and distinct-failure arms fail RED against a version-blind single-exception decoder.
 *
 * Excludes:
 * - Credential storage, DataStore persistence and device-measured KDF work factors.
 */
class SettingsExchangeEnvelopeContractTest : DataFunSpec() {
    init {
        test("envelope metadata outside the pinned profile is rejected before key derivation") {
            // Structural rejection happens at the boundary, typed as a format failure — these
            // never reach key derivation at all.
            shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(version = 3), "pw")
            }
            shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(kdf = "PBKDF2WithHmacSHA1"), "pw")
            }
            shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(cipher = "AES/CBC/PKCS5Padding"), "pw")
            }
            shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(iterations = Int.MAX_VALUE), "pw")
            }
        }

        test("version 2 envelope metadata is AEAD-bound: ciphertext forged without AAD fails the tag") {
            // Forge a structurally valid version-2 envelope whose ciphertext was produced
            // without updateAAD. If the production decrypt did not authenticate metadata, this
            // would succeed — it must fail the GCM tag instead.
            val forged = forgedEnvelopeWithoutAad(password = "pw", plainText = "settings".toByteArray())
            shouldThrow<MigrationPasswordException> { decryptSettings(forged, "pw") }
        }

        test("corrupted or unsupported envelope is a distinct failure, not a password failure") {
            val unsupported = shouldThrow<MigrationEnvelopeException> {
                decryptSettings(envelopeJson(version = 3), "pw")
            }
            unsupported.shouldNotBeInstanceOf<MigrationPasswordException>()

            val malformed = shouldThrow<MigrationEnvelopeException> { decryptSettings("{not json", "pw") }
            malformed.shouldNotBeInstanceOf<MigrationPasswordException>()
        }

        test("wire payload with unknown settingsSchemaVersion is refused, not silently decoded") {
            // Wire schemaVersion is a real gate: a payload from a future build refuses to decode
            // rather than silently interpreting fields it does not know.
            shouldThrow<Exception> {
                migrationJson.decodeFromString<MigrationSettingsSnapshot>(
                    """{"preferences":{},"sensitive":{},"settingsSchemaVersion":9}""",
                )
            }
            // Unknown top-level fields are refused outright instead of being silently dropped.
            shouldThrow<Exception> {
                migrationJson.decodeFromString<MigrationSettingsSnapshot>(
                    """{"preferences":{},"sensitive":{},"futureField":"x"}""",
                )
            }
        }

        test("manifest schemaVersion reflects the validated wire value") {
            val snapshot =
                migrationJson.decodeFromString<MigrationSettingsSnapshot>(
                    """{"preferences":{},"sensitive":{},"settingsSchemaVersion":1}""",
                )
            snapshot.settingsSchemaVersion shouldBe DataStoreMigrationSettingsStore.migrationSettingsSchemaVersion
            snapshot.toValidationReport().manifest.schemaVersion shouldBe
                DataStoreMigrationSettingsStore.migrationSettingsSchemaVersion
        }

        test("ciphertext tamper still fails the GCM tag") {
            val envelope = encryptSettings("payload".toByteArray(), "pw")
            val tampered = tamperCipherTextFirstChar(envelope)
            shouldThrow<MigrationPasswordException> { decryptSettings(tampered, "pw") }
        }

        test("oversized envelope text is rejected before parsing") {
            val oversized = "x".repeat(SETTINGS_MAX_ENVELOPE_BYTES + 1)
            shouldThrow<MigrationEnvelopeException> { decryptSettings(oversized, "pw") }
        }
    }

    private fun envelopeJson(
        version: Int = 1,
        kdf: String = "PBKDF2WithHmacSHA256",
        cipher: String = "AES/GCM/NoPadding",
        iterations: Int = 120_000,
        saltBase64: String = "AAAAAAAAAAAAAAAAAAAAAA==",
        nonceBase64: String = "AAAAAAAAAAAAAAAA",
        cipherTextBase64: String = "AAAA",
    ): String =
        "{" +
            "\"version\":$version," +
            "\"kdf\":\"$kdf\"," +
            "\"cipher\":\"$cipher\"," +
            "\"iterations\":$iterations," +
            "\"saltBase64\":\"$saltBase64\"," +
            "\"nonceBase64\":\"$nonceBase64\"," +
            "\"cipherTextBase64\":\"$cipherTextBase64\"" +
            "}"

    /**
     * Produces a structurally valid version-2 envelope whose GCM tag was computed without the
     * production AAD — a faithful replica of what an attacker (or a no-AAD implementation)
     * could assemble with a known password.
     */
    private fun forgedEnvelopeWithoutAad(
        password: String,
        plainText: ByteArray,
    ): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
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
            "\"version\":2," +
            "\"kdf\":\"PBKDF2WithHmacSHA256\"," +
            "\"cipher\":\"AES/GCM/NoPadding\"," +
            "\"iterations\":600000," +
            "\"saltBase64\":\"${encoder.encodeToString(salt)}\"," +
            "\"nonceBase64\":\"${encoder.encodeToString(nonce)}\"," +
            "\"cipherTextBase64\":\"${encoder.encodeToString(cipherText)}\"" +
            "}"
    }

    private fun tamperCipherTextFirstChar(envelope: String): String {
        val marker = "\"cipherTextBase64\":\""
        val start = envelope.indexOf(marker) + marker.length
        val first = envelope[start]
        val flipped = if (first == 'A') 'B' else 'A'
        return envelope.replaceRange(start, start + 1, flipped.toString())
    }
}
