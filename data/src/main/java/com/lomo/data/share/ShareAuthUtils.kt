package com.lomo.data.share

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared authentication helpers for LAN share requests.
 *
 * Uses user-provided pairing code (stored as derived key hex) to sign requests with HMAC-SHA256.
 */
internal object ShareAuthUtils {
    private const val KEY_DERIVATION_SALT = "lomo-lan-share-v1"
    private const val NONCE_BYTES = 16
    const val AUTH_WINDOW_MS = 2 * 60 * 1000L
    private val secureRandom = SecureRandom()

    fun deriveKeyHexFromPairingCode(pairingCode: String): String? {
        val normalized = pairingCode.trim()
        if (normalized.length !in 6..64) return null
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$KEY_DERIVATION_SALT:$normalized".toByteArray(Charsets.UTF_8))
        return digest.toHexString()
    }

    fun isValidKeyHex(keyHex: String?): Boolean {
        if (keyHex.isNullOrBlank()) return false
        return keyHex.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.toHexString()
    }

    fun isTimestampWithinWindow(
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = AUTH_WINDOW_MS,
    ): Boolean {
        return kotlin.math.abs(nowMs - timestampMs) <= windowMs
    }

    fun buildPreparePayloadToSign(
        senderName: String,
        encryptedContent: String,
        contentNonce: String,
        timestamp: Long,
        attachmentNames: List<String>,
        authTimestampMs: Long,
        authNonce: String,
    ): String {
        val canonicalAttachments = attachmentNames.map { it.trim() }.sorted()
        return buildString {
            append("prepare\n")
            append(senderName).append('\n')
            append(timestamp).append('\n')
            append(encryptedContent).append('\n')
            append(contentNonce).append('\n')
            canonicalAttachments.forEach { append(it).append('\n') }
            append(authTimestampMs).append('\n')
            append(authNonce)
        }
    }

    fun buildTransferPayloadToSign(
        sessionToken: String,
        encryptedContent: String,
        contentNonce: String,
        timestamp: Long,
        attachmentNames: List<String>,
        authTimestampMs: Long,
        authNonce: String,
    ): String {
        val canonicalAttachments = attachmentNames.map { it.trim() }.sorted()
        return buildString {
            append("transfer\n")
            append(sessionToken).append('\n')
            append(timestamp).append('\n')
            append(encryptedContent).append('\n')
            append(contentNonce).append('\n')
            canonicalAttachments.forEach { append(it).append('\n') }
            append(authTimestampMs).append('\n')
            append(authNonce)
        }
    }

    fun signPayloadHex(
        keyHex: String,
        payload: String,
    ): String {
        val keyBytes = keyHex.hexToBytes()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHexString()
    }

    fun verifySignature(
        keyHex: String,
        payload: String,
        providedSignatureHex: String,
    ): Boolean {
        if (!providedSignatureHex.matches(Regex("^[0-9a-fA-F]{64}$"))) return false
        val expected = signPayloadHex(keyHex, payload)
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.UTF_8),
            providedSignatureHex.lowercase().toByteArray(Charsets.UTF_8),
        )
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
