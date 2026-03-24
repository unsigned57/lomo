package com.lomo.data.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.ShareTransferLimits
import timber.log.Timber
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class ShareTransferAttachment(
    val name: String,
    val nonceBase64: String?,
    val contentType: String,
    val payloadFile: File?,
    val sourceUri: Uri?,
    val payloadSizeBytes: Long?,
)

internal data class ShareTransferRequestPayload(
    val payloadContent: String,
    val contentNonce: String,
    val authTimestampMs: Long,
    val authNonce: String,
    val authSignature: String,
    val attachments: List<ShareTransferAttachment>,
    val tempPayloadFiles: List<File>,
) {
    fun toMetadata(
        sessionToken: String,
        timestamp: Long,
        e2eEnabled: Boolean,
    ): LomoShareServer.TransferMetadata =
        LomoShareServer.TransferMetadata(
            sessionToken = sessionToken,
            encryptedContent = payloadContent,
            contentNonce = contentNonce,
            timestamp = timestamp,
            e2eEnabled = e2eEnabled,
            attachmentNames = attachments.map { it.name },
            attachmentNonces =
                attachments
                    .mapNotNull { attachment -> attachment.nonceBase64?.let { attachment.name to it } }
                    .toMap(),
            authTimestampMs = authTimestampMs,
            authNonce = authNonce,
            authSignature = authSignature,
        )
}

internal class ShareTransferPayloadBuilder(
    private val context: Context,
) {
    fun build(
        content: String,
        timestamp: Long,
        sessionToken: String,
        attachmentUris: Map<String, Uri>,
        e2eEnabled: Boolean,
        keyHex: String?,
    ): ShareTransferRequestPayload {
        require(ShareTransferLimits.isAttachmentCountValid(attachmentUris.size)) {
            "Too many attachments"
        }
        return if (e2eEnabled) {
            buildEncryptedPayload(
                content = content,
                timestamp = timestamp,
                sessionToken = sessionToken,
                attachmentUris = attachmentUris,
                keyHex = requireNotNull(keyHex) { "Missing E2E key" },
            )
        } else {
            buildOpenPayload(content, attachmentUris)
        }
    }

    private fun buildEncryptedPayload(
        content: String,
        timestamp: Long,
        sessionToken: String,
        attachmentUris: Map<String, Uri>,
        keyHex: String,
    ): ShareTransferRequestPayload {
        val encryptedContent =
            ShareCryptoUtils.encryptText(
                keyHex = keyHex,
                plaintext = content,
                aad = MEMO_CONTENT_AAD,
            )
        val attachments = mutableListOf<ShareTransferAttachment>()
        val tempPayloadFiles = mutableListOf<File>()
        var totalEncryptedAttachmentBytes = 0L
        for ((filename, uri) in attachmentUris) {
            val encrypted =
                encryptUriToTempFile(
                    uri = uri,
                    keyHex = keyHex,
                    aad = "$ATTACHMENT_AAD_PREFIX$filename",
                    maxBytes = ShareTransferLimits.maxAttachmentPayloadBytes(e2eEnabled = false),
                ) ?: throw IllegalStateException("Failed to read attachment: $filename")
            val payloadFile = encrypted.payloadFile
            tempPayloadFiles += payloadFile
            totalEncryptedAttachmentBytes += payloadFile.length()
            if (totalEncryptedAttachmentBytes > ShareTransferLimits.maxTotalAttachmentPayloadBytes(e2eEnabled = true)) {
                throw IllegalArgumentException("Attachments too large")
            }
            attachments +=
                ShareTransferAttachment(
                    name = filename,
                    nonceBase64 = encrypted.nonceBase64,
                    contentType = guessContentType(filename),
                    payloadFile = payloadFile,
                    sourceUri = null,
                    payloadSizeBytes = payloadFile.length(),
                )
        }

        val authTimestampMs = System.currentTimeMillis()
        val authNonce = ShareAuthUtils.generateNonce()
        val signaturePayload =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = sessionToken,
                encryptedContent = encryptedContent.ciphertextBase64,
                contentNonce = encryptedContent.nonceBase64,
                timestamp = timestamp,
                attachmentNames = attachments.map { it.name },
                authTimestampMs = authTimestampMs,
                authNonce = authNonce,
            )
        return ShareTransferRequestPayload(
            payloadContent = encryptedContent.ciphertextBase64,
            contentNonce = encryptedContent.nonceBase64,
            authTimestampMs = authTimestampMs,
            authNonce = authNonce,
            authSignature = ShareAuthUtils.signPayloadHex(keyHex = keyHex, payload = signaturePayload),
            attachments = attachments,
            tempPayloadFiles = tempPayloadFiles,
        )
    }

    private fun buildOpenPayload(
        content: String,
        attachmentUris: Map<String, Uri>,
    ): ShareTransferRequestPayload {
        val attachments = mutableListOf<ShareTransferAttachment>()
        var totalAttachmentBytes = 0L
        for ((filename, uri) in attachmentUris) {
            val size = resolveUriSize(uri)
            if (size != null) {
                if (size > ShareTransferLimits.maxAttachmentPayloadBytes(e2eEnabled = false)) {
                    throw IllegalArgumentException("Attachment too large")
                }
                totalAttachmentBytes += size
                if (totalAttachmentBytes > ShareTransferLimits.maxTotalAttachmentPayloadBytes(e2eEnabled = false)) {
                    throw IllegalArgumentException("Attachments too large")
                }
            }
            attachments +=
                ShareTransferAttachment(
                    name = filename,
                    nonceBase64 = null,
                    contentType = guessContentType(filename),
                    payloadFile = null,
                    sourceUri = uri,
                    payloadSizeBytes = size,
                )
        }
        return ShareTransferRequestPayload(
            payloadContent = content,
            contentNonce = "",
            authTimestampMs = 0L,
            authNonce = "",
            authSignature = "",
            attachments = attachments,
            tempPayloadFiles = emptyList(),
        )
    }

    private fun resolveUriSize(uri: Uri): Long? {
        val queriedSize =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val hasSize = cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)
                    if (hasSize) cursor.getLong(index).takeIf { it >= 0L } else null
                }
            }.getOrNull()
        val fileSize =
            uri.path
                ?.takeIf { uri.scheme == FILE_SCHEME && it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.exists() && it.isFile }
                ?.length()
                ?.takeIf { it >= 0L }
        return queriedSize ?: fileSize
    }

    private data class EncryptedTempPayload(
        val payloadFile: File,
        val nonceBase64: String,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun encryptUriToTempFile(
        uri: Uri,
        keyHex: String,
        aad: String,
        maxBytes: Long,
    ): EncryptedTempPayload? {
        var payloadFile: File? = null
        val input =
            context.contentResolver.openInputStream(uri)
                ?: return null
        return runNonFatalCatching {
            input.use { source ->
                val nonce = ShareCryptoUtils.generateNonce()
                val cipher = ShareCryptoUtils.createEncryptCipher(keyHex = keyHex, nonce = nonce, aad = aad)
                val encryptedFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, context.cacheDir)
                payloadFile = encryptedFile
                writeEncryptedPayload(source, encryptedFile, cipher, maxBytes)
                EncryptedTempPayload(
                    payloadFile = encryptedFile,
                    nonceBase64 = Base64.Default.encode(nonce),
                )
            }
        }.getOrElse { error ->
            payloadFile?.let { file -> runCatching { file.delete() } }
            Timber.tag(TAG).e(error, "Failed to encrypt URI: $uri")
            null
        }
    }

    private fun writeEncryptedPayload(
        source: java.io.InputStream,
        encryptedFile: File,
        cipher: javax.crypto.Cipher,
        maxBytes: Long,
    ) {
        encryptedFile.outputStream().buffered().use { encryptedOut ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalPlainBytes = 0L
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) {
                    break
                }
                totalPlainBytes += read.toLong()
                if (totalPlainBytes > maxBytes) {
                    throw IllegalArgumentException("Attachment too large")
                }
                cipher.update(buffer, 0, read)?.takeIf { it.isNotEmpty() }?.let(encryptedOut::write)
            }
            cipher.doFinal().takeIf { it.isNotEmpty() }?.let(encryptedOut::write)
        }
    }

    private fun guessContentType(filename: String): String =
        when {
            filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
            filename.endsWith(".png", true) -> "image/png"
            filename.endsWith(".webp", true) -> "image/webp"
            filename.endsWith(".gif", true) -> "image/gif"
            filename.endsWith(".m4a", true) -> "audio/mp4"
            filename.endsWith(".mp3", true) -> "audio/mpeg"
            filename.endsWith(".ogg", true) -> "audio/ogg"
            filename.endsWith(".wav", true) -> "audio/wav"
            else -> "application/octet-stream"
        }

    private companion object {
        private const val ATTACHMENT_AAD_PREFIX = "attachment:"
        private const val FILE_SCHEME = "file"
        private const val MEMO_CONTENT_AAD = "memo-content"
        private const val TAG = "LomoShareClient"
        private const val TEMP_FILE_PREFIX = "share_payload_"
        private const val TEMP_FILE_SUFFIX = ".bin"
    }
}
