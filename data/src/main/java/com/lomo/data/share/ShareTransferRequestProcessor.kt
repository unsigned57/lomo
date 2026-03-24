package com.lomo.data.share

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.ShareTransferLimits
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

internal typealias ShareAttachmentSaver = suspend (name: String, type: String, payloadFile: File) -> String?
internal typealias ShareMemoSaver =
    suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit

internal class ShareTransferRequestProcessor(
    private val json: Json,
    private val isE2eEnabled: suspend () -> Boolean,
    private val validateTransferMetadata: (LomoShareServer.TransferMetadata) -> String?,
    private val validateTransferAuthentication: suspend (LomoShareServer.TransferMetadata) -> ShareAuthValidation,
    private val consumeApprovedSession: (
        sessionToken: String,
        requestHash: String,
        attachmentNames: Set<String>,
        e2eEnabled: Boolean,
    ) -> Boolean,
    private val buildRequestHash: (
        content: String,
        timestamp: Long,
        attachmentNames: List<String>,
        e2eEnabled: Boolean,
    ) -> String,
    private val onSaveAttachment: () -> ShareAttachmentSaver?,
    private val onSaveMemo: () -> ShareMemoSaver?,
) {
    suspend fun handle(call: ApplicationCall) {
        val tempAttachmentFiles = mutableListOf<File>()
        try {
            val error =
                runNonFatalCatching {
                    ensureTransferContentLength(call)
                    val state = TransferProcessingState()
                    val multipart = call.receiveMultipart()
                    var part = multipart.readPart()
                    while (part != null) {
                        try {
                            when (part) {
                                is io.ktor.http.content.PartData.FormItem -> processFormItem(part, state)
                                is io.ktor.http.content.PartData.FileItem ->
                                    processFileItem(part, state, tempAttachmentFiles)
                                else -> Unit
                            }
                        } finally {
                            part.dispose()
                        }
                        part = multipart.readPart()
                    }
                    completeTransfer(state)
                    call.respondText(
                        json.encodeToString(
                            LomoShareServer.TransferResponse.serializer(),
                            LomoShareServer.TransferResponse(true),
                        ),
                        ContentType.Application.Json,
                    )
                }.exceptionOrNull()
            when (error) {
                null -> Unit
                is TransferRejectedException -> call.respond(error.status, error.message)
                is IllegalArgumentException -> {
                    Timber.tag(TAG).w(error, "Rejected transfer payload")
                    call.respond(HttpStatusCode.PayloadTooLarge, error.message ?: "Transfer payload too large")
                }
                else -> {
                    Timber.tag(TAG).e(error, "Error handling transfer request")
                    call.respond(HttpStatusCode.InternalServerError, "Transfer error")
                }
            }
        } finally {
            tempAttachmentFiles.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    private suspend fun processFormItem(
        part: io.ktor.http.content.PartData.FormItem,
        state: TransferProcessingState,
    ) {
        if (part.name != METADATA_FORM_KEY) {
            return
        }
        if (state.metadata != null) {
            rejectTransfer(HttpStatusCode.BadRequest, "Duplicate metadata")
        }

        val metadata = json.decodeFromString<LomoShareServer.TransferMetadata>(part.value)
        val authValidation = validateMetadataState(metadata)
        val decryptedContent = decryptContent(metadata, authValidation.keyHex)
        validateDecryptedContent(decryptedContent)
        val normalizedNames = metadata.attachmentNames.map(String::trim)
        val requestHash =
            buildRequestHash(
                decryptedContent,
                metadata.timestamp,
                normalizedNames,
                metadata.e2eEnabled,
            )
        if (!consumeApprovedSession(metadata.sessionToken, requestHash, normalizedNames.toSet(), metadata.e2eEnabled)) {
            rejectTransfer(HttpStatusCode.Forbidden, "Invalid or expired share session")
        }

        state.metadata = metadata
        state.transferKeyHex = authValidation.keyHex
        state.decryptedContent = decryptedContent
        state.expectedAttachmentNames = normalizedNames.toSet()
        state.expectedAttachmentNonces =
            if (metadata.e2eEnabled) {
                metadata.attachmentNonces.mapKeys { it.key.trim() }
            } else {
                emptyMap()
            }
        state.expectedByUploadName = buildUploadNameLookup(state.expectedAttachmentNames)
    }

    private suspend fun validateMetadataState(metadata: LomoShareServer.TransferMetadata): ShareAuthValidation {
        val localE2eEnabled = isE2eEnabled()
        if (metadata.e2eEnabled != localE2eEnabled) {
            rejectTransfer(HttpStatusCode.PreconditionFailed, "Encryption mode mismatch")
        }
        if (!metadata.e2eEnabled) {
            Timber.tag(TAG).w("Received OPEN mode transfer metadata (unauthenticated)")
        }
        validateTransferMetadata(metadata)?.let { error ->
            rejectTransfer(HttpStatusCode.BadRequest, error)
        }
        val authValidation = validateTransferAuthentication(metadata)
        if (!authValidation.ok) {
            rejectTransfer(authValidation.status, authValidation.message)
        }
        if (metadata.e2eEnabled && authValidation.keyHex.isNullOrBlank()) {
            rejectTransfer(HttpStatusCode.PreconditionFailed, "Missing pairing key")
        }
        return authValidation
    }

    private fun decryptContent(
        metadata: LomoShareServer.TransferMetadata,
        keyHex: String?,
    ): String =
        if (metadata.e2eEnabled) {
            ShareCryptoUtils.decryptText(
                keyHex = keyHex.orEmpty(),
                ciphertextBase64 = metadata.encryptedContent,
                nonceBase64 = metadata.contentNonce,
                aad = MEMO_CONTENT_AAD,
            ) ?: rejectTransfer(HttpStatusCode.Unauthorized, "Cannot decrypt transfer content")
        } else {
            metadata.encryptedContent
        }

    private fun validateDecryptedContent(content: String) {
        if (content.length > ShareTransferLimits.maxMemoChars(e2eEnabled = false)) {
            rejectTransfer(HttpStatusCode.PayloadTooLarge, "Memo content too large")
        }
    }

    private suspend fun processFileItem(
        part: io.ktor.http.content.PartData.FileItem,
        state: TransferProcessingState,
        tempAttachmentFiles: MutableList<File>,
    ) {
        val metadata =
            state.metadata ?: rejectTransfer(HttpStatusCode.BadRequest, "Metadata must be sent before attachments")
        val expectedReferenceName = resolveExpectedAttachmentName(part.originalFileName, state)
        if (!state.receivedAttachmentNames.add(expectedReferenceName)) {
            rejectTransfer(HttpStatusCode.BadRequest, "Duplicate attachment")
        }

        val type =
            when {
                part.contentType?.match(ContentType.Image.Any) == true -> IMAGE_TYPE
                part.contentType?.match(ContentType.Audio.Any) == true -> AUDIO_TYPE
                else -> UNKNOWN_TYPE
            }
        if (type == UNKNOWN_TYPE) {
            rejectTransfer(HttpStatusCode.UnsupportedMediaType, "Unsupported attachment type")
        }

        val tempAttachment = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX)
        tempAttachmentFiles += tempAttachment
        val streamed =
            if (metadata.e2eEnabled) {
                decryptPartToTempFile(
                    part = part,
                    outputFile = tempAttachment,
                    keyHex =
                        state.transferKeyHex
                            ?: rejectTransfer(HttpStatusCode.PreconditionFailed, "Missing transfer key"),
                    nonceBase64 =
                        state.expectedAttachmentNonces[expectedReferenceName]
                            ?: rejectTransfer(HttpStatusCode.BadRequest, "Missing attachment nonce"),
                    aad = "$ATTACHMENT_AAD_PREFIX$expectedReferenceName",
                    maxCipherBytes = ShareTransferLimits.maxAttachmentPayloadBytes(metadata.e2eEnabled),
                    maxPlainBytes = ShareTransferLimits.MAX_ATTACHMENT_SIZE_BYTES,
                ) ?: rejectTransfer(HttpStatusCode.Unauthorized, "Cannot decrypt attachment")
            } else {
                copyPartToTempFile(
                    part = part,
                    outputFile = tempAttachment,
                    maxBytes = ShareTransferLimits.maxAttachmentPayloadBytes(metadata.e2eEnabled),
                )
            }

        state.totalAttachmentBytes += streamed.totalInputBytes
        if (state.totalAttachmentBytes > ShareTransferLimits.maxTotalAttachmentPayloadBytes(metadata.e2eEnabled)) {
            rejectTransfer(HttpStatusCode.PayloadTooLarge, "Attachments too large")
        }

        Timber
            .tag(TAG)
            .d("Received attachment: $expectedReferenceName ($type, ${streamed.plaintextBytes} bytes)")

        val savedPath =
            onSaveAttachment()
                ?.invoke(sanitizeStorageFilename(expectedReferenceName), type, tempAttachment)
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Failed to save attachment: $expectedReferenceName")
        state.attachmentMappings[expectedReferenceName] = savedPath
    }

    private fun resolveExpectedAttachmentName(
        uploadName: String?,
        state: TransferProcessingState,
    ): String {
        val normalizedName = uploadName?.trim()
        val expectedReferenceName =
            when {
                normalizedName.isNullOrEmpty() -> null
                normalizedName in state.expectedAttachmentNames -> normalizedName
                else -> state.expectedByUploadName[baseAttachmentName(normalizedName)]
            }
        return expectedReferenceName ?: rejectTransfer(HttpStatusCode.BadRequest, "Unexpected attachment")
    }

    private suspend fun completeTransfer(state: TransferProcessingState) {
        val metadata = state.metadata ?: rejectTransfer(HttpStatusCode.BadRequest, "Missing metadata")
        if (state.expectedAttachmentNames != state.receivedAttachmentNames) {
            rejectTransfer(HttpStatusCode.BadRequest, "Attachment set mismatch")
        }

        val memoSaver =
            onSaveMemo() ?: throw IllegalStateException("Memo saver callback is not configured")
        val contentToSave =
            state.decryptedContent ?: rejectTransfer(HttpStatusCode.BadRequest, "Missing decrypted content")
        memoSaver.invoke(contentToSave, metadata.timestamp, state.attachmentMappings)
        Timber.tag(TAG).d("Memo saved with ${state.attachmentMappings.size} attachments")
    }

    private fun copyPartToTempFile(
        part: io.ktor.http.content.PartData.FileItem,
        outputFile: File,
        maxBytes: Long,
    ): StreamedAttachment {
        part.provider().toInputStream().use { input ->
            outputFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read.toLong()
                    if (total > maxBytes) {
                        attachmentTooLarge()
                    }
                    output.write(buffer, 0, read)
                }
                return StreamedAttachment(totalInputBytes = total, plaintextBytes = total)
            }
        }
    }

    private fun decryptPartToTempFile(
        part: io.ktor.http.content.PartData.FileItem,
        outputFile: File,
        keyHex: String,
        nonceBase64: String,
        aad: String,
        maxCipherBytes: Long,
        maxPlainBytes: Long,
    ): StreamedAttachment? {
        val nonce = ShareCryptoUtils.decodeNonceBase64(nonceBase64) ?: return null
        return try {
            val cipher = ShareCryptoUtils.createDecryptCipher(keyHex = keyHex, nonce = nonce, aad = aad)
            part.provider().toInputStream().use { input ->
                writeDecryptedPart(
                    input = input,
                    outputFile = outputFile,
                    cipher = cipher,
                    maxCipherBytes = maxCipherBytes,
                    maxPlainBytes = maxPlainBytes,
                )
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDecryptedPart(
        input: java.io.InputStream,
        outputFile: File,
        cipher: javax.crypto.Cipher,
        maxCipherBytes: Long,
        maxPlainBytes: Long,
    ): StreamedAttachment {
        outputFile.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalCipherBytes = 0L
            var totalPlainBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                totalCipherBytes += read.toLong()
                if (totalCipherBytes > maxCipherBytes) {
                    attachmentTooLarge()
                }
                cipher.update(buffer, 0, read)?.takeIf { it.isNotEmpty() }?.let { plainChunk ->
                    totalPlainBytes += plainChunk.size.toLong()
                    if (totalPlainBytes > maxPlainBytes) {
                        attachmentTooLarge()
                    }
                    output.write(plainChunk)
                }
            }
            cipher.doFinal().takeIf { it.isNotEmpty() }?.let { finalChunk ->
                totalPlainBytes += finalChunk.size.toLong()
                if (totalPlainBytes > maxPlainBytes) {
                    attachmentTooLarge()
                }
                output.write(finalChunk)
            }
            return StreamedAttachment(
                totalInputBytes = totalCipherBytes,
                plaintextBytes = totalPlainBytes,
            )
        }
    }

    private class TransferProcessingState(
        var metadata: LomoShareServer.TransferMetadata? = null,
        var transferKeyHex: String? = null,
        var decryptedContent: String? = null,
        var expectedAttachmentNames: Set<String> = emptySet(),
        var expectedAttachmentNonces: Map<String, String> = emptyMap(),
        var expectedByUploadName: Map<String, String> = emptyMap(),
        val attachmentMappings: MutableMap<String, String> = mutableMapOf(),
        val receivedAttachmentNames: MutableSet<String> = mutableSetOf(),
        var totalAttachmentBytes: Long = 0L,
    )

    private data class StreamedAttachment(
        val totalInputBytes: Long,
        val plaintextBytes: Long,
    )

    private companion object {
        private const val ATTACHMENT_AAD_PREFIX = "attachment:"
        private const val AUDIO_TYPE = "audio"
        private const val IMAGE_TYPE = "image"
        private const val MEMO_CONTENT_AAD = "memo-content"
        private const val METADATA_FORM_KEY = "metadata"
        private const val TAG = "LomoShareServer"
        private const val TEMP_FILE_PREFIX = "share_incoming_"
        private const val TEMP_FILE_SUFFIX = ".bin"
        private const val UNKNOWN_TYPE = "unknown"
    }
}

private fun ensureTransferContentLength(call: ApplicationCall) {
    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > ShareTransferLimits.MAX_TRANSFER_BODY_BYTES) {
        rejectTransfer(HttpStatusCode.PayloadTooLarge, "Transfer payload too large")
    }
}

private fun rejectTransfer(
    status: HttpStatusCode,
    message: String,
): Nothing = throw TransferRejectedException(status, message)

private fun attachmentTooLarge(): Nothing = throw IllegalArgumentException("Attachment too large")

private class TransferRejectedException(
    val status: HttpStatusCode,
    override val message: String,
) : IllegalStateException(message)

private fun sanitizeStorageFilename(referenceName: String): String {
    val baseName =
        referenceName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    val cleaned =
        baseName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_STORAGE_FILENAME_CHARS)
    return if (cleaned.isBlank()) {
        "attachment_${System.currentTimeMillis()}"
    } else {
        cleaned
    }
}

private fun baseAttachmentName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')

private fun buildUploadNameLookup(expectedAttachmentNames: Set<String>): Map<String, String> {
    val grouped = expectedAttachmentNames.groupBy(::baseAttachmentName)
    return grouped
        .mapNotNull { (uploadName, refs) ->
            if (uploadName.isBlank() || refs.size != 1) {
                null
            } else {
                uploadName to refs.first()
            }
        }.toMap()
}

private const val MAX_STORAGE_FILENAME_CHARS = 96
