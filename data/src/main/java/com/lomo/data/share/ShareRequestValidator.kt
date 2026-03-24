package com.lomo.data.share

import com.lomo.data.share.LomoShareServer.PrepareRequest
import com.lomo.data.share.LomoShareServer.TransferMetadata
import com.lomo.domain.model.ShareTransferLimits
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal class ShareRequestValidator {
    fun validatePrepareRequest(request: PrepareRequest): String? {
        val normalizedNames = request.attachments.map { it.name.trim() }
        return listOfNotNull(
            request.senderName
                .takeUnless(::isValidSenderName)
                ?.let { "Invalid sender name" },
            request.encryptedContent
                .takeIf(String::isBlank)
                ?.let { "Memo content is empty" },
            validateMemoPayload(
                encryptedContent = request.encryptedContent,
                contentNonce = request.contentNonce,
                authTimestampMs = request.authTimestampMs,
                authNonce = request.authNonce,
                authSignature = request.authSignature,
                attachmentNonces = emptyMap(),
                e2eEnabled = request.e2eEnabled,
            ),
            request.attachments
                .takeUnless { ShareTransferLimits.isAttachmentCountValid(it.size) }
                ?.let { "Too many attachments" },
            validateAttachmentNames(normalizedNames),
            request.attachments.firstNotNullOfOrNull { attachment ->
                validateAttachment(attachment.name.trim(), attachment.type, attachment.size)
            },
        ).firstOrNull()
    }

    fun validateTransferMetadata(metadata: TransferMetadata): String? {
        val normalizedNames = metadata.attachmentNames.map(String::trim)
        return listOfNotNull(
            metadata.sessionToken
                .takeIf(String::isBlank)
                ?.let { "Missing share session token" },
            metadata.encryptedContent
                .takeIf(String::isBlank)
                ?.let { "Memo content is empty" },
            validateMemoPayload(
                encryptedContent = metadata.encryptedContent,
                contentNonce = metadata.contentNonce,
                authTimestampMs = metadata.authTimestampMs,
                authNonce = metadata.authNonce,
                authSignature = metadata.authSignature,
                attachmentNonces = metadata.attachmentNonces,
                e2eEnabled = metadata.e2eEnabled,
            ),
            metadata.attachmentNames
                .takeUnless { ShareTransferLimits.isAttachmentCountValid(it.size) }
                ?.let { "Too many attachments" },
            validateAttachmentNames(normalizedNames),
            validateAttachmentNonces(
                attachmentNames = normalizedNames,
                attachmentNonces = metadata.attachmentNonces,
                e2eEnabled = metadata.e2eEnabled,
            ),
        ).firstOrNull()
    }

    private fun validateMemoPayload(
        encryptedContent: String,
        contentNonce: String,
        authTimestampMs: Long,
        authNonce: String,
        authSignature: String,
        attachmentNonces: Map<String, String>,
        e2eEnabled: Boolean,
    ): String? =
        if (e2eEnabled) {
            listOfNotNull(
                encryptedContent
                    .takeUnless { it.length <= ShareTransferLimits.maxMemoChars(e2eEnabled = true) }
                    ?.let { "Encrypted memo content too large" },
                contentNonce
                    .takeUnless(::isValidContentNonce)
                    ?.let { "Invalid content nonce" },
                authTimestampMs
                    .takeUnless { it > 0L }
                    ?.let { "Invalid auth timestamp" },
                authNonce
                    .takeUnless(::isValidAuthNonce)
                    ?.let { "Invalid auth nonce" },
                authSignature
                    .takeUnless(::isValidSignatureHex)
                    ?.let { "Invalid auth signature" },
            ).firstOrNull()
        } else {
            listOfNotNull(
                encryptedContent
                    .takeUnless { it.length <= ShareTransferLimits.maxMemoChars(e2eEnabled = false) }
                    ?.let { "Memo content too large" },
                contentNonce
                    .takeIf(String::isNotBlank)
                    ?.let { "Content nonce is not allowed in open mode" },
                authTimestampMs
                    .takeIf { hasOpenModeAuthFields(it, authNonce, authSignature) }
                    ?.let { "Auth fields are not allowed in open mode" },
                attachmentNonces
                    .takeIf(Map<String, String>::isNotEmpty)
                    ?.let { "Attachment nonces are not allowed in open mode" },
            ).firstOrNull()
        }

    private fun validateAttachmentNames(names: List<String>): String? {
        val seenNames = mutableSetOf<String>()
        return names.firstNotNullOfOrNull { name ->
            when {
                !isValidAttachmentReferenceName(name) -> "Invalid attachment name"
                !seenNames.add(name) -> "Duplicate attachment name"
                else -> null
            }
        }
    }

    private fun validateAttachment(
        name: String,
        type: String,
        size: Long,
    ): String? =
        when {
            !isValidAttachmentReferenceName(name) -> "Invalid attachment name"
            type !in SUPPORTED_ATTACHMENT_TYPES -> "Unsupported attachment type"
            size < 0L -> "Invalid attachment size"
            size > ShareTransferLimits.MAX_ATTACHMENT_SIZE_BYTES -> "Attachment too large"
            else -> null
        }

    private fun validateAttachmentNonces(
        attachmentNames: List<String>,
        attachmentNonces: Map<String, String>,
        e2eEnabled: Boolean,
    ): String? =
        if (!e2eEnabled) {
            null
        } else {
            val seenNonceNames = mutableSetOf<String>()
            val normalizedNonces = mutableListOf<String>()
            val nonceNameError =
                attachmentNonces.entries.firstNotNullOfOrNull { (rawName, nonce) ->
                    val name = rawName.trim()
                    when {
                        !isValidAttachmentReferenceName(name) -> "Invalid attachment nonce name"
                        !seenNonceNames.add(name) -> "Duplicate attachment nonce name"
                        else -> {
                            normalizedNonces += nonce
                            null
                        }
                    }
                }
            listOfNotNull(
                attachmentNonces
                    .takeUnless { ShareTransferLimits.isAttachmentCountValid(it.size) }
                    ?.let { "Too many attachment nonces" },
                nonceNameError,
                seenNonceNames
                    .takeIf { it != attachmentNames.toSet() }
                    ?.let { "Attachment nonce mismatch" },
                normalizedNonces
                    .firstOrNull { nonce -> !isValidContentNonce(nonce) }
                    ?.let { "Invalid attachment nonce" },
            ).firstOrNull()
        }

    private fun isValidSenderName(name: String): Boolean = name.isNotBlank() && name.length <= MAX_SENDER_NAME_CHARS

    private fun isValidAttachmentReferenceName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= MAX_ATTACHMENT_NAME_CHARS &&
            !name.contains('\u0000')

    private fun isValidAuthNonce(nonce: String): Boolean {
        if (nonce.isBlank() || nonce.length > MAX_AUTH_NONCE_CHARS) return false
        return nonce.matches(Regex("^[0-9a-fA-F]+$"))
    }

    private fun isValidContentNonce(nonceBase64: String): Boolean {
        if (nonceBase64.isBlank() || nonceBase64.length > MAX_NONCE_BASE64_CHARS) return false
        return try {
            Base64.Default.decode(nonceBase64).size == NONCE_BYTES
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidSignatureHex(signature: String): Boolean = signature.matches(Regex("^[0-9a-fA-F]{64}$"))

    private companion object {
        private val SUPPORTED_ATTACHMENT_TYPES = setOf("image", "audio")
        private const val NONCE_BYTES = 12
        private const val MAX_SENDER_NAME_CHARS = 64
        private const val MAX_ATTACHMENT_NAME_CHARS = 1024
        private const val MAX_AUTH_NONCE_CHARS = 64
        private const val MAX_NONCE_BASE64_CHARS = 64
    }
}

private fun hasOpenModeAuthFields(
    authTimestampMs: Long,
    authNonce: String,
    authSignature: String,
): Boolean = authTimestampMs != 0L || authNonce.isNotBlank() || authSignature.isNotBlank()
