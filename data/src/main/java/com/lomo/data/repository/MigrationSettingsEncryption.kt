package com.lomo.data.repository

import com.lomo.domain.usecase.MigrationPasswordException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val SETTINGS_VERSION = 1
private const val SETTINGS_KDF = "PBKDF2WithHmacSHA256"
private const val SETTINGS_CIPHER = "AES/GCM/NoPadding"
private const val SETTINGS_KEY_BITS = 256
private const val SETTINGS_GCM_TAG_BITS = 128
private const val SETTINGS_SALT_BYTES = 16
private const val SETTINGS_NONCE_BYTES = 12
private const val SETTINGS_KDF_ITERATIONS = 120_000

@Serializable
private data class EncryptedMigrationSettingsEnvelope(
    val version: Int = SETTINGS_VERSION,
    val kdf: String = SETTINGS_KDF,
    val cipher: String = SETTINGS_CIPHER,
    val iterations: Int = SETTINGS_KDF_ITERATIONS,
    val saltBase64: String,
    val nonceBase64: String,
    val cipherTextBase64: String,
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
        deriveSettingsKey(password, salt, SETTINGS_KDF_ITERATIONS),
        GCMParameterSpec(SETTINGS_GCM_TAG_BITS, nonce),
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

internal fun decryptSettings(
    envelopeText: String,
    password: String,
): ByteArray =
    try {
        val envelope = migrationJson.decodeFromString<EncryptedMigrationSettingsEnvelope>(envelopeText)
        require(envelope.version == SETTINGS_VERSION) {
            "Unsupported migration settings version: ${envelope.version}"
        }
        require(envelope.kdf == SETTINGS_KDF) { "Unsupported migration settings KDF: ${envelope.kdf}" }
        require(envelope.cipher == SETTINGS_CIPHER) { "Unsupported migration settings cipher: ${envelope.cipher}" }
        val cipher = Cipher.getInstance(SETTINGS_CIPHER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveSettingsKey(password, envelope.saltBase64.fromBase64(), envelope.iterations),
            GCMParameterSpec(SETTINGS_GCM_TAG_BITS, envelope.nonceBase64.fromBase64()),
        )
        cipher.doFinal(envelope.cipherTextBase64.fromBase64())
    } catch (exception: AEADBadTagException) {
        throw MigrationPasswordException(cause = exception)
    } catch (exception: IllegalArgumentException) {
        throw MigrationPasswordException("Migration settings file is not valid", exception)
    } catch (exception: SerializationException) {
        throw MigrationPasswordException("Migration settings file is not valid", exception)
    } catch (exception: GeneralSecurityException) {
        throw MigrationPasswordException(cause = exception)
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
