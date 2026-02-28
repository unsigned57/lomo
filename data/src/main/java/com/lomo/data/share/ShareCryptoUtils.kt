package com.lomo.data.share

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Content encryption helpers for LAN share payloads.
 *
 * All memo content and attachment bytes are encrypted with AES-GCM before transport.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object ShareCryptoUtils {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val ENC_DOMAIN = "lomo-lan-share-enc-v1"
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
            ciphertextBase64 = Base64.Default.encode(encrypted.ciphertext),
            nonceBase64 = encrypted.nonceBase64,
        )
    }

    fun decryptText(
        keyHex: String,
        ciphertextBase64: String,
        nonceBase64: String,
        aad: String? = null,
    ): String? =
        try {
            val ciphertext = Base64.Default.decode(ciphertextBase64)
            val plaintext = decryptBytesInternal(keyHex, ciphertext, nonceBase64, aad)
            plaintext?.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
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

    fun createEncryptCipher(
        keyHex: String,
        nonce: ByteArray,
        aad: String? = null,
    ): Cipher = createCipher(Cipher.ENCRYPT_MODE, keyHex, nonce, aad)

    fun createDecryptCipher(
        keyHex: String,
        nonce: ByteArray,
        aad: String? = null,
    ): Cipher = createCipher(Cipher.DECRYPT_MODE, keyHex, nonce, aad)

    fun generateNonce(): ByteArray = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }

    fun decodeNonceBase64(nonceBase64: String): ByteArray? =
        try {
            Base64.Default.decode(nonceBase64).takeIf { it.size == NONCE_BYTES }
        } catch (_: Exception) {
            null
        }

    private fun encryptBytesInternal(
        keyHex: String,
        plaintext: ByteArray,
        aad: String?,
    ): EncryptedBytes {
        val nonce = generateNonce()
        val cipher = createEncryptCipher(keyHex, nonce, aad)
        val encrypted = cipher.doFinal(plaintext)
        return EncryptedBytes(
            ciphertext = encrypted,
            nonceBase64 = Base64.Default.encode(nonce),
        )
    }

    private fun decryptBytesInternal(
        keyHex: String,
        ciphertext: ByteArray,
        nonceBase64: String,
        aad: String?,
    ): ByteArray? {
        return try {
            val nonce = decodeNonceBase64(nonceBase64) ?: return null
            val cipher = createDecryptCipher(keyHex, nonce, aad)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private fun createCipher(
        mode: Int,
        keyHex: String,
        nonce: ByteArray,
        aad: String?,
    ): Cipher {
        require(nonce.size == NONCE_BYTES) { "Invalid nonce length" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(deriveEncryptionKey(keyHex), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (!aad.isNullOrEmpty()) {
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        return cipher
    }

    private fun deriveEncryptionKey(keyHex: String): ByteArray {
        val baseKey = keyHex.hexToBytes()
        val input = ENC_DOMAIN.toByteArray(Charsets.UTF_8) + baseKey
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
