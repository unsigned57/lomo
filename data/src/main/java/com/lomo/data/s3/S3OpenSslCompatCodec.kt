package com.lomo.data.s3

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class S3OpenSslCompatCodec(
    private val saltGenerator: () -> ByteArray = ::generateSalt,
) {
    fun encryptContent(
        plaintext: String,
        password: String,
    ): String = encryptString(plaintext, password)

    fun decryptContent(
        encrypted: String,
        password: String,
    ): String = decryptString(encrypted, password)

    fun encryptKey(
        key: String,
        password: String,
    ): String = encryptString(key, password)

    fun decryptKey(
        encryptedKey: String,
        password: String,
    ): String = decryptString(encryptedKey, password)

    fun encryptBytes(
        plaintext: ByteArray,
        password: String,
    ): ByteArray {
        require(password.isNotBlank()) { "Encryption password is required" }
        val salt = saltGenerator()
        require(salt.size == SALT_SIZE_BYTES) { "OpenSSL-compatible salt must be 8 bytes" }

        val keyMaterial = deriveKeyMaterial(password, salt)
        val cipher = newCipher(Cipher.ENCRYPT_MODE, keyMaterial)
        val ciphertext = cipher.doFinal(plaintext)
        return OPENSSL_PREFIX + salt + ciphertext
    }

    fun decryptBytes(
        encrypted: ByteArray,
        password: String,
    ): ByteArray {
        require(password.isNotBlank()) { "Encryption password is required" }
        validatePayload(encrypted)

        val saltStart = OPENSSL_PREFIX.size
        val saltEnd = saltStart + SALT_SIZE_BYTES
        val salt = encrypted.copyOfRange(saltStart, saltEnd)
        val ciphertext = encrypted.copyOfRange(saltEnd, encrypted.size)
        val keyMaterial = deriveKeyMaterial(password, salt)
        val cipher = newCipher(Cipher.DECRYPT_MODE, keyMaterial)
        return try {
            cipher.doFinal(ciphertext)
        } catch (error: IllegalBlockSizeException) {
            throw IllegalArgumentException("Failed to decrypt OpenSSL payload", error)
        } catch (error: BadPaddingException) {
            throw IllegalArgumentException("Failed to decrypt OpenSSL payload", error)
        }
    }

    private fun encryptString(
        value: String,
        password: String,
    ): String {
        val payload = encryptBytes(value.toByteArray(StandardCharsets.UTF_8), password)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload)
    }

    private fun decryptString(
        encodedValue: String,
        password: String,
    ): String {
        val payload = decodeOpenSslPayload(encodedValue)
        return decryptBytes(payload, password).toString(StandardCharsets.UTF_8)
    }

    private fun deriveKeyMaterial(
        password: String,
        salt: ByteArray,
    ): DerivedKeyMaterial {
        val keySpec =
            PBEKeySpec(
                password.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_MATERIAL_BITS,
            )
        val secretFactory = SecretKeyFactory.getInstance(KEY_FACTORY)
        val keyBytes = secretFactory.generateSecret(keySpec).encoded
        return DerivedKeyMaterial(
            key = keyBytes.copyOfRange(0, AES_KEY_SIZE_BYTES),
            iv = keyBytes.copyOfRange(AES_KEY_SIZE_BYTES, KEY_MATERIAL_SIZE_BYTES),
        )
    }

    private fun newCipher(
        mode: Int,
        keyMaterial: DerivedKeyMaterial,
    ): Cipher =
        Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(
                mode,
                SecretKeySpec(keyMaterial.key, AES_KEY_ALGORITHM),
                IvParameterSpec(keyMaterial.iv),
            )
        }

    private data class DerivedKeyMaterial(
        val key: ByteArray,
        val iv: ByteArray,
    )

}

private const val AES_KEY_ALGORITHM = "AES"
private const val CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding"
private const val KEY_FACTORY = "PBKDF2WithHmacSHA256"
private const val PBKDF2_ITERATIONS = 20_000
private const val AES_KEY_SIZE_BYTES = 32
private const val KEY_MATERIAL_SIZE_BYTES = 48
private const val KEY_MATERIAL_BITS = KEY_MATERIAL_SIZE_BYTES * 8
private const val SALT_SIZE_BYTES = 8
private val OPENSSL_PREFIX = "Salted__".toByteArray(StandardCharsets.UTF_8)

private fun generateSalt(): ByteArray = ByteArray(SALT_SIZE_BYTES).also(SecureRandom()::nextBytes)

private fun invalidBase64Payload(error: Throwable): ByteArray {
    throw IllegalArgumentException("Encrypted payload is not valid base64url", error)
}

private fun decodeOpenSslPayload(encodedValue: String): ByteArray =
    runCatching {
        Base64.getUrlDecoder().decode(encodedValue)
    }.getOrElse(::invalidBase64Payload)

private fun validatePayload(payload: ByteArray) {
    require(payload.size > OPENSSL_PREFIX.size + SALT_SIZE_BYTES) {
        "Encrypted payload is too short"
    }
    val prefix = payload.copyOfRange(0, OPENSSL_PREFIX.size)
    require(prefix.contentEquals(OPENSSL_PREFIX)) {
        "Encrypted payload does not use OpenSSL salt header"
    }
}
