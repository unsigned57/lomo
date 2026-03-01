package com.lomo.data.share

import com.lomo.data.share.LomoShareServer.PrepareRequest
import com.lomo.data.share.LomoShareServer.TransferMetadata
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal class ShareRequestValidator {
    fun validatePrepareRequest(request: PrepareRequest): String? {
        if (request.senderName.isBlank() || request.senderName.length > MAX_SENDER_NAME_CHARS) {
            return "Invalid sender name"
        }
        if (request.encryptedContent.isBlank()) {
            return "Memo content is empty"
        }
        if (request.e2eEnabled) {
            if (request.encryptedContent.length > ShareTransferLimits.maxMemoChars(e2eEnabled = true)) {
                return "Encrypted memo content too large"
            }
            if (!isValidContentNonce(request.contentNonce)) {
                return "Invalid content nonce"
            }
            if (request.authTimestampMs <= 0L) {
                return "Invalid auth timestamp"
            }
            if (!isValidAuthNonce(request.authNonce)) {
                return "Invalid auth nonce"
            }
            if (!isValidSignatureHex(request.authSignature)) {
                return "Invalid auth signature"
            }
        } else {
            if (request.encryptedContent.length > ShareTransferLimits.maxMemoChars(e2eEnabled = false)) {
                return "Memo content too large"
            }
            if (request.contentNonce.isNotBlank()) {
                return "Content nonce is not allowed in open mode"
            }
            if (request.authTimestampMs != 0L || request.authNonce.isNotBlank() || request.authSignature.isNotBlank()) {
                return "Auth fields are not allowed in open mode"
            }
        }
        if (!ShareTransferLimits.isAttachmentCountValid(request.attachments.size)) {
            return "Too many attachments"
        }

        val seenNames = mutableSetOf<String>()
        for (attachment in request.attachments) {
            val name = attachment.name.trim()
            if (!isValidAttachmentReferenceName(name)) {
                return "Invalid attachment name"
            }
            if (!seenNames.add(name)) {
                return "Duplicate attachment name"
            }
            if (attachment.type !in setOf("image", "audio")) {
                return "Unsupported attachment type"
            }
            if (attachment.size < 0) {
                return "Invalid attachment size"
            }
            if (attachment.size > ShareTransferLimits.MAX_ATTACHMENT_SIZE_BYTES) {
                return "Attachment too large"
            }
        }
        return null
    }

    fun validateTransferMetadata(metadata: TransferMetadata): String? {
        if (metadata.sessionToken.isBlank()) {
            return "Missing share session token"
        }
        if (metadata.encryptedContent.isBlank()) {
            return "Memo content is empty"
        }
        if (metadata.e2eEnabled) {
            if (metadata.encryptedContent.length > ShareTransferLimits.maxMemoChars(e2eEnabled = true)) {
                return "Encrypted memo content too large"
            }
            if (!isValidContentNonce(metadata.contentNonce)) {
                return "Invalid content nonce"
            }
            if (metadata.authTimestampMs <= 0L) {
                return "Invalid auth timestamp"
            }
            if (!isValidAuthNonce(metadata.authNonce)) {
                return "Invalid auth nonce"
            }
            if (!isValidSignatureHex(metadata.authSignature)) {
                return "Invalid auth signature"
            }
        } else {
            if (metadata.encryptedContent.length > ShareTransferLimits.maxMemoChars(e2eEnabled = false)) {
                return "Memo content too large"
            }
            if (metadata.contentNonce.isNotBlank()) {
                return "Content nonce is not allowed in open mode"
            }
            if (metadata.authTimestampMs != 0L || metadata.authNonce.isNotBlank() || metadata.authSignature.isNotBlank()) {
                return "Auth fields are not allowed in open mode"
            }
            if (metadata.attachmentNonces.isNotEmpty()) {
                return "Attachment nonces are not allowed in open mode"
            }
        }
        if (!ShareTransferLimits.isAttachmentCountValid(metadata.attachmentNames.size)) {
            return "Too many attachments"
        }

        val seenNames = mutableSetOf<String>()
        val normalizedNames = metadata.attachmentNames.map { it.trim() }
        for (name in normalizedNames) {
            if (!isValidAttachmentReferenceName(name)) {
                return "Invalid attachment name"
            }
            if (!seenNames.add(name)) {
                return "Duplicate attachment name"
            }
        }
        if (metadata.e2eEnabled) {
            if (!ShareTransferLimits.isAttachmentCountValid(metadata.attachmentNonces.size)) {
                return "Too many attachment nonces"
            }
            val normalizedAttachmentNonces = mutableMapOf<String, String>()
            for ((rawName, nonce) in metadata.attachmentNonces) {
                val trimmedName = rawName.trim()
                if (!isValidAttachmentReferenceName(trimmedName)) {
                    return "Invalid attachment nonce name"
                }
                if (normalizedAttachmentNonces.put(trimmedName, nonce) != null) {
                    return "Duplicate attachment nonce name"
                }
            }
            if (normalizedAttachmentNonces.keys != seenNames) {
                return "Attachment nonce mismatch"
            }
            for ((_, nonce) in normalizedAttachmentNonces) {
                if (!isValidContentNonce(nonce)) {
                    return "Invalid attachment nonce"
                }
            }
        }
        return null
    }

    private fun isValidAttachmentReferenceName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_ATTACHMENT_NAME_CHARS) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun isValidAuthNonce(nonce: String): Boolean {
        if (nonce.isBlank() || nonce.length > MAX_AUTH_NONCE_CHARS) return false
        return nonce.matches(Regex("^[0-9a-fA-F]+$"))
    }

    private fun isValidContentNonce(nonceBase64: String): Boolean {
        if (nonceBase64.isBlank() || nonceBase64.length > MAX_NONCE_BASE64_CHARS) return false
        return try {
            Base64.Default.decode(nonceBase64).size == 12
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidSignatureHex(signature: String): Boolean = signature.matches(Regex("^[0-9a-fA-F]{64}$"))

    private companion object {
        private const val MAX_SENDER_NAME_CHARS = 64
        private const val MAX_ATTACHMENT_NAME_CHARS = 1024
        private const val MAX_AUTH_NONCE_CHARS = 64
        private const val MAX_NONCE_BASE64_CHARS = 64
    }
}
