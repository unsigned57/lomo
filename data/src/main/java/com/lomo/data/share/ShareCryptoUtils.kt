package com.lomo.data.share

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Content encryption helpers for LAN share payloads.
 *
 * All memo content and attachment bytes are encrypted with AES-GCM before transport.
 */
internal object ShareCryptoUtils {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val secureRandom = SecureRandom()

    data class EncryptedText(
        val ciphertextBase64: String,
        val nonceBase64: String,
    )

    data class EncryptedBytes(
        val ciphertext: ByteArray,
        val nonceBase64: String,
    )

    fun encryptText(
        keyHex: String,
        plaintext: String,
        aad: String? = null,
    ): EncryptedText {
        val encrypted = encryptBytesInternal(keyHex, plaintext.toByteArray(Charsets.UTF_8), aad)
        return EncryptedText(
            ciphertextBase64 = Base64.getEncoder().encodeToString(encrypted.ciphertext),
            nonceBase64 = encrypted.nonceBase64,
        )
    }

    fun decryptText(
        keyHex: String,
        ciphertextBase64: String,
        nonceBase64: String,
        aad: String? = null,
    ): String? {
        return try {
            val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
            val plaintext = decryptBytesInternal(keyHex, ciphertext, nonceBase64, aad)
            plaintext?.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun encryptBytes(
        keyHex: String,
        plaintext: ByteArray,
        aad: String? = null,
    ): EncryptedBytes = encryptBytesInternal(keyHex, plaintext, aad)

    fun decryptBytes(
        keyHex: String,
        ciphertext: ByteArray,
        nonceBase64: String,
        aad: String? = null,
    ): ByteArray? = decryptBytesInternal(keyHex, ciphertext, nonceBase64, aad)

    private fun encryptBytesInternal(
        keyHex: String,
        plaintext: ByteArray,
        aad: String?,
    ): EncryptedBytes {
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveEncryptionKey(keyHex), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (!aad.isNullOrEmpty()) {
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        val encrypted = cipher.doFinal(plaintext)
        return EncryptedBytes(
            ciphertext = encrypted,
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
        )
    }

    private fun decryptBytesInternal(
        keyHex: String,
        ciphertext: ByteArray,
        nonceBase64: String,
        aad: String?,
    ): ByteArray? {
        return try {
            val nonce = Base64.getDecoder().decode(nonceBase64)
            if (nonce.size != NONCE_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveEncryptionKey(keyHex), "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (!aad.isNullOrEmpty()) {
                cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            }
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveEncryptionKey(keyHex: String): ByteArray {
        val baseKey = keyHex.hexToBytes()
        val input = "lomo-lan-share-enc-v1".toByteArray(Charsets.UTF_8) + baseKey
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

