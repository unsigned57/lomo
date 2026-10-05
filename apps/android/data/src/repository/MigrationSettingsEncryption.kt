package com.lomo.data.repository

import com.lomo.domain.usecase.MigrationEnvelopeException
import com.lomo.domain.usecase.MigrationPasswordException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val SETTINGS_VERSION_LEGACY = 1
private const val SETTINGS_VERSION_CURRENT = 2
private const val SETTINGS_KDF = "PBKDF2WithHmacSHA256"
private const val SETTINGS_CIPHER = "AES/GCM/NoPadding"
private const val SETTINGS_KEY_BITS = 256
private const val SETTINGS_GCM_TAG_BITS = 128
private const val SETTINGS_SALT_BYTES = 16
private const val SETTINGS_NONCE_BYTES = 12

/**
 * PBKDF2 work factors, per envelope version. The legacy factor is exactly what version-1
 * production exports wrote; the version-2 factor was raised after host-side measurement
 * (120k ≈ 29 ms / 600k ≈ 107 ms on the dev host — on-device verification still tracked in the
 * repair record) and remains a hard pin so an envelope cannot ask for unbounded work.
 */
private const val SETTINGS_KDF_ITERATIONS_LEGACY = 120_000
private const val SETTINGS_KDF_ITERATIONS_CURRENT = 600_000

private const val SETTINGS_MAX_CIPHER_TEXT_BYTES = 1 shl 20
private const val SETTINGS_MAX_ENVELOPE_CHARS = 4 shl 20

/** Bytes-cap for the wire envelope as read from an untrusted stream (JSON is ASCII/base64). */
internal const val SETTINGS_MAX_ENVELOPE_BYTES = SETTINGS_MAX_ENVELOPE_CHARS

@Serializable
private data class EncryptedMigrationSettingsEnvelope(
    val version: Int = SETTINGS_VERSION_CURRENT,
    val kdf: String = SETTINGS_KDF,
    val cipher: String = SETTINGS_CIPHER,
    val iterations: Int = SETTINGS_KDF_ITERATIONS_CURRENT,
    val saltBase64: String,
    val nonceBase64: String,
    val cipherTextBase64: String,
)

/**
 * The decryption profile a given envelope [version] is allowed to use. Each version name pins
 * exactly the parameters that production builds actually emitted for it, so "known old
 * version" never widens into "any plausible parameters".
 *
 * [metadataAuthenticated] selects whether the envelope fields are bound into the GCM tag via
 * AAD. Version 1 envelopes predate AAD and decrypt without it; version 2 envelopes bind
 * version/kdf/cipher/iterations so metadata tampering can never slide under a valid tag.
 */
private data class EnvelopeProfile(
    val iterations: Int,
    val metadataAuthenticated: Boolean,
)

private val ENVELOPE_PROFILES: Map<Int, EnvelopeProfile> =
    mapOf(
        SETTINGS_VERSION_LEGACY to
            EnvelopeProfile(
                iterations = SETTINGS_KDF_ITERATIONS_LEGACY,
                metadataAuthenticated = false,
            ),
        SETTINGS_VERSION_CURRENT to
            EnvelopeProfile(
                iterations = SETTINGS_KDF_ITERATIONS_CURRENT,
                metadataAuthenticated = true,
            ),
    )

internal fun encryptSettings(
    plainText: ByteArray,
    password: String,
): String {
    val random = SecureRandom()
    val salt = ByteArray(SETTINGS_SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(SETTINGS_NONCE_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance(SETTINGS_CIPHER)
    cipher.init(
        Cipher.ENCRYPT_MODE,
        deriveSettingsKey(password, salt, SETTINGS_KDF_ITERATIONS_CURRENT),
        GCMParameterSpec(SETTINGS_GCM_TAG_BITS, nonce),
    )
    cipher.updateAAD(
        envelopeAssociatedData(
            version = SETTINGS_VERSION_CURRENT,
            kdf = SETTINGS_KDF,
            cipher = SETTINGS_CIPHER,
            iterations = SETTINGS_KDF_ITERATIONS_CURRENT,
        ),
    )
    val cipherText = cipher.doFinal(plainText)
    return migrationJson.encodeToString(
        EncryptedMigrationSettingsEnvelope(
            saltBase64 = salt.base64(),
            nonceBase64 = nonce.base64(),
            cipherTextBase64 = cipherText.base64(),
        ),
    )
}

/**
 * Reads an encrypted-settings envelope off an untrusted stream without materializing more than
 * [SETTINGS_MAX_ENVELOPE_BYTES]. An over-budget stream is rejected before the bytes can
 * accumulate unboundedly.
 */
internal fun InputStream.readSettingsEnvelopeText(): String {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val out = java.io.ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, SETTINGS_MAX_ENVELOPE_BYTES - total + 1))
        if (read == -1) break
        total += read
        if (total > SETTINGS_MAX_ENVELOPE_BYTES) {
            throw MigrationEnvelopeException("Migration settings envelope exceeds its size budget")
        }
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}

/**
 * Canonical additional-authenticated-data for envelope [version] ≥ 2: binds every field that
 * steers decryption (format version, KDF/cipher names, iteration count) into the GCM tag so a
 * metadata edit is indistinguishable from a corrupted ciphertext.
 */
private fun envelopeAssociatedData(
    version: Int,
    kdf: String,
    cipher: String,
    iterations: Int,
): ByteArray =
    (
        "lomo-settings-envelope" +
            "|version=" + version +
            "|kdf=" + kdf +
            "|cipher=" + cipher +
            "|iterations=" + iterations
    ).toByteArray(Charsets.UTF_8)

/** Single throw site for envelope-format failures — keeps the parser's `throw` count honest. */
private fun envelopeFailure(message: String, cause: Throwable? = null): Nothing =
    throw MigrationEnvelopeException(message, cause)

/** Envelope fields that survived format validation and are cleared for decryption. */
private data class ValidatedEnvelope(
    val envelope: EncryptedMigrationSettingsEnvelope,
    val profile: EnvelopeProfile,
    val salt: ByteArray,
    val nonce: ByteArray,
    val cipherText: ByteArray,
)

/**
 * Format gate for [decryptSettings]: every envelope invariant is checked here, before any key
 * derivation or cipher work, so malformed/oversized input never reaches the expensive path.
 * All failures are [MigrationEnvelopeException] — never [MigrationPasswordException].
 */
private fun parseAndValidateEnvelope(envelopeText: String): ValidatedEnvelope {
    if (envelopeText.length > SETTINGS_MAX_ENVELOPE_CHARS) {
        envelopeFailure("Migration settings envelope exceeds its size budget")
    }
    val envelope =
        try {
            migrationJson.decodeFromString<EncryptedMigrationSettingsEnvelope>(envelopeText)
        } catch (exception: SerializationException) {
            envelopeFailure("Migration settings envelope is not valid JSON", exception)
        }
    val profile =
        ENVELOPE_PROFILES[envelope.version]
            ?: envelopeFailure("Unsupported migration settings version: ${envelope.version}")
    if (envelope.kdf != SETTINGS_KDF) {
        envelopeFailure("Unsupported migration settings KDF: ${envelope.kdf}")
    }
    if (envelope.cipher != SETTINGS_CIPHER) {
        envelopeFailure("Unsupported migration settings cipher: ${envelope.cipher}")
    }
    // Iteration budget is enforced before any key derivation can burn CPU.
    if (envelope.iterations != profile.iterations) {
        envelopeFailure("Unsupported migration settings KDF iterations: ${envelope.iterations}")
    }
    val salt =
        try {
            envelope.saltBase64.fromBase64()
        } catch (exception: IllegalArgumentException) {
            envelopeFailure("Migration settings salt is not valid base64", exception)
        }
    val nonce =
        try {
            envelope.nonceBase64.fromBase64()
        } catch (exception: IllegalArgumentException) {
            envelopeFailure("Migration settings nonce is not valid base64", exception)
        }
    val cipherText =
        try {
            envelope.cipherTextBase64.fromBase64()
        } catch (exception: IllegalArgumentException) {
            envelopeFailure("Migration settings payload is not valid base64", exception)
        }
    if (salt.size != SETTINGS_SALT_BYTES) {
        envelopeFailure("Migration settings salt has an invalid length")
    }
    if (nonce.size != SETTINGS_NONCE_BYTES) {
        envelopeFailure("Migration settings nonce has an invalid length")
    }
    if (cipherText.size !in 1..SETTINGS_MAX_CIPHER_TEXT_BYTES) {
        envelopeFailure("Migration settings payload has an invalid length")
    }
    return ValidatedEnvelope(
        envelope = envelope,
        profile = profile,
        salt = salt,
        nonce = nonce,
        cipherText = cipherText,
    )
}

internal fun decryptSettings(
    envelopeText: String,
    password: String,
): ByteArray {
    val validated = parseAndValidateEnvelope(envelopeText)
    return try {
        val cipher = Cipher.getInstance(SETTINGS_CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveSettingsKey(password, validated.salt, validated.envelope.iterations),
            GCMParameterSpec(SETTINGS_GCM_TAG_BITS, validated.nonce),
        )
        if (validated.profile.metadataAuthenticated) {
            cipher.updateAAD(
                envelopeAssociatedData(
                    version = validated.envelope.version,
                    kdf = validated.envelope.kdf,
                    cipher = validated.envelope.cipher,
                    iterations = validated.envelope.iterations,
                ),
            )
        }
        cipher.doFinal(validated.cipherText)
    } catch (exception: AEADBadTagException) {
        // AEAD cannot separate "wrong password" from "tampered ciphertext/AAD" — both surface as
        // a password failure. Malformed envelopes and unsupported versions fail earlier with
        // MigrationEnvelopeException and never reach the tag check.
        throw MigrationPasswordException(cause = exception)
    } catch (exception: GeneralSecurityException) {
        envelopeFailure("Migration settings envelope could not be decrypted", exception)
    }
}

private fun deriveSettingsKey(
    password: String,
    salt: ByteArray,
    iterations: Int,
): SecretKeySpec {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, SETTINGS_KEY_BITS)
    val factory = SecretKeyFactory.getInstance(SETTINGS_KDF)
    return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
}

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

private fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)
