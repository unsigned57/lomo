package com.lomo.data.share

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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class LomoShareTransferHandler(
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
    private val onSaveAttachment: () -> (suspend (name: String, type: String, payloadFile: File) -> String?)?,
    private val onSaveMemo: () -> (suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit)?,
) {
    suspend fun handle(call: ApplicationCall) {
        val tempAttachmentFiles = mutableListOf<File>()
        try {
            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_TRANSFER_BODY_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, "Transfer payload too large")
                return
            }

            val multipart = call.receiveMultipart()
            var metadata: LomoShareServer.TransferMetadata? = null
            var transferKeyHex: String? = null
            var decryptedContent: String? = null
            var expectedAttachmentNames = emptySet<String>()
            var expectedAttachmentNonces = emptyMap<String, String>()
            var expectedByUploadName = emptyMap<String, String>()
            val attachmentMappings = mutableMapOf<String, String>()
            val receivedAttachmentNames = mutableSetOf<String>()
            var totalAttachmentBytes = 0L

            var part = multipart.readPart()
            while (part != null) {
                when (part) {
                    is io.ktor.http.content.PartData.FormItem -> {
                        if (part.name == "metadata") {
                            if (metadata != null) {
                                part.dispose()
                                call.respond(HttpStatusCode.BadRequest, "Duplicate metadata")
                                return
                            }
                            val meta = json.decodeFromString<LomoShareServer.TransferMetadata>(part.value)
                            metadata = meta
                            val localE2eEnabled = isE2eEnabled()
                            if (meta.e2eEnabled != localE2eEnabled) {
                                part.dispose()
                                call.respond(HttpStatusCode.PreconditionFailed, "Encryption mode mismatch")
                                return
                            }
                            if (!meta.e2eEnabled) {
                                Timber.tag(TAG).w("Received OPEN mode transfer metadata (unauthenticated)")
                            }
                            val metaError = validateTransferMetadata(meta)
                            if (metaError != null) {
                                part.dispose()
                                call.respond(HttpStatusCode.BadRequest, metaError)
                                return
                            }
                            val authValidation = validateTransferAuthentication(meta)
                            if (!authValidation.ok) {
                                part.dispose()
                                call.respond(authValidation.status, authValidation.message)
                                return
                            }
                            val keyHex = authValidation.keyHex
                            if (meta.e2eEnabled && keyHex.isNullOrBlank()) {
                                part.dispose()
                                call.respond(HttpStatusCode.PreconditionFailed, "Missing pairing key")
                                return
                            }
                            val plainContent =
                                if (meta.e2eEnabled) {
                                    ShareCryptoUtils.decryptText(
                                        keyHex = keyHex ?: "",
                                        ciphertextBase64 = meta.encryptedContent,
                                        nonceBase64 = meta.contentNonce,
                                        aad = "memo-content",
                                    )
                                } else {
                                    meta.encryptedContent
                                }
                            if (plainContent == null) {
                                part.dispose()
                                call.respond(HttpStatusCode.Unauthorized, "Cannot decrypt transfer content")
                                return
                            }
                            if (plainContent.length > MAX_MEMO_CHARS) {
                                part.dispose()
                                call.respond(HttpStatusCode.PayloadTooLarge, "Memo content too large")
                                return
                            }

                            val normalizedNames = meta.attachmentNames.map { it.trim() }
                            val requestHash =
                                buildRequestHash(
                                    plainContent,
                                    meta.timestamp,
                                    normalizedNames,
                                    meta.e2eEnabled,
                                )
                            val validSession =
                                consumeApprovedSession(
                                    meta.sessionToken,
                                    requestHash,
                                    normalizedNames.toSet(),
                                    meta.e2eEnabled,
                                )
                            if (!validSession) {
                                part.dispose()
                                call.respond(HttpStatusCode.Forbidden, "Invalid or expired share session")
                                return
                            }
                            expectedAttachmentNames = normalizedNames.toSet()
                            expectedAttachmentNonces =
                                if (meta.e2eEnabled) {
                                    meta.attachmentNonces.mapKeys { it.key.trim() }
                                } else {
                                    emptyMap()
                                }
                            expectedByUploadName = buildUploadNameLookup(expectedAttachmentNames)
                            transferKeyHex = keyHex
                            decryptedContent = plainContent
                        }
                    }

                    is io.ktor.http.content.PartData.FileItem -> {
                        if (metadata == null) {
                            part.dispose()
                            call.respond(HttpStatusCode.BadRequest, "Metadata must be sent before attachments")
                            return
                        }

                        val uploadName = part.originalFileName?.trim()
                        val expectedReferenceName =
                            when {
                                uploadName.isNullOrEmpty() -> null
                                uploadName in expectedAttachmentNames -> uploadName
                                else -> expectedByUploadName[baseAttachmentName(uploadName)]
                            }
                        if (expectedReferenceName == null) {
                            part.dispose()
                            call.respond(HttpStatusCode.BadRequest, "Unexpected attachment")
                            return
                        }
                        if (!receivedAttachmentNames.add(expectedReferenceName)) {
                            part.dispose()
                            call.respond(HttpStatusCode.BadRequest, "Duplicate attachment")
                            return
                        }

                        val type =
                            when {
                                part.contentType?.match(ContentType.Image.Any) == true -> "image"
                                part.contentType?.match(ContentType.Audio.Any) == true -> "audio"
                                else -> "unknown"
                            }
                        if (type == "unknown") {
                            part.dispose()
                            call.respond(HttpStatusCode.UnsupportedMediaType, "Unsupported attachment type")
                            return
                        }

                        val currentMetadata = metadata
                        val perAttachmentMax =
                            if (currentMetadata.e2eEnabled) {
                                MAX_ATTACHMENT_ENCRYPTED_SIZE_BYTES
                            } else {
                                MAX_ATTACHMENT_SIZE_BYTES
                            }
                        val totalAttachmentMax =
                            if (currentMetadata.e2eEnabled) {
                                MAX_TOTAL_ATTACHMENT_ENCRYPTED_BYTES
                            } else {
                                MAX_TOTAL_ATTACHMENT_BYTES
                            }

                        val tempAttachment = File.createTempFile("share_incoming_", ".bin")
                        tempAttachmentFiles += tempAttachment
                        val streamed =
                            if (currentMetadata.e2eEnabled) {
                                val attachmentNonce = expectedAttachmentNonces[expectedReferenceName]
                                if (attachmentNonce.isNullOrBlank()) {
                                    part.dispose()
                                    call.respond(HttpStatusCode.BadRequest, "Missing attachment nonce")
                                    return
                                }
                                val keyHex = transferKeyHex
                                if (keyHex.isNullOrBlank()) {
                                    part.dispose()
                                    call.respond(HttpStatusCode.PreconditionFailed, "Missing transfer key")
                                    return
                                }
                                decryptPartToTempFile(
                                    part = part,
                                    outputFile = tempAttachment,
                                    keyHex = keyHex,
                                    nonceBase64 = attachmentNonce,
                                    aad = "attachment:$expectedReferenceName",
                                    maxCipherBytes = perAttachmentMax,
                                    maxPlainBytes = MAX_ATTACHMENT_SIZE_BYTES,
                                )
                            } else {
                                copyPartToTempFile(
                                    part = part,
                                    outputFile = tempAttachment,
                                    maxBytes = perAttachmentMax,
                                )
                            }
                        if (streamed == null) {
                            part.dispose()
                            call.respond(HttpStatusCode.Unauthorized, "Cannot decrypt attachment")
                            return
                        }

                        totalAttachmentBytes += streamed.totalInputBytes
                        if (totalAttachmentBytes > totalAttachmentMax) {
                            part.dispose()
                            call.respond(HttpStatusCode.PayloadTooLarge, "Attachments too large")
                            return
                        }

                        val safeStorageName = sanitizeStorageFilename(expectedReferenceName)
                        Timber
                            .tag(TAG)
                            .d("Received attachment: $expectedReferenceName ($type, ${streamed.plaintextBytes} bytes)")

                        val attachmentSaver = onSaveAttachment()
                        val savedPath =
                            attachmentSaver?.invoke(
                                safeStorageName,
                                type,
                                tempAttachment,
                            )
                        if (!savedPath.isNullOrBlank()) {
                            attachmentMappings[expectedReferenceName] = savedPath
                        } else {
                            throw IllegalStateException("Failed to save attachment: $expectedReferenceName")
                        }
                    }

                    else -> {}
                }
                part.dispose()
                part = multipart.readPart()
            }

            val meta = metadata
            if (meta == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing metadata")
                return
            }

            if (expectedAttachmentNames != receivedAttachmentNames) {
                call.respond(HttpStatusCode.BadRequest, "Attachment set mismatch")
                return
            }

            val memoSaver = onSaveMemo()
            if (memoSaver == null) {
                throw IllegalStateException("Memo saver callback is not configured")
            }
            val contentToSave = decryptedContent
            if (contentToSave == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing decrypted content")
                return
            }
            memoSaver.invoke(contentToSave, meta.timestamp, attachmentMappings)
            Timber.tag(TAG).d("Memo saved with ${attachmentMappings.size} attachments")

            call.respondText(
                json.encodeToString(
                    LomoShareServer.TransferResponse.serializer(),
                    LomoShareServer.TransferResponse(true),
                ),
                ContentType.Application.Json,
            )
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w(e, "Rejected transfer payload")
            call.respond(HttpStatusCode.PayloadTooLarge, e.message ?: "Transfer payload too large")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling transfer request")
            call.respond(HttpStatusCode.InternalServerError, "Transfer error")
        } finally {
            tempAttachmentFiles.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

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

    private data class StreamedAttachment(
        val totalInputBytes: Long,
        val plaintextBytes: Long,
    )

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
                        throw IllegalArgumentException("Attachment too large")
                    }
                    output.write(buffer, 0, read)
                }
                return StreamedAttachment(totalInputBytes = total, plaintextBytes = total)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decryptPartToTempFile(
        part: io.ktor.http.content.PartData.FileItem,
        outputFile: File,
        keyHex: String,
        nonceBase64: String,
        aad: String,
        maxCipherBytes: Long,
        maxPlainBytes: Long,
    ): StreamedAttachment? {
        val nonce =
            try {
                Base64.Default.decode(nonceBase64)
            } catch (_: Exception) {
                return null
            }
        if (nonce.size != NONCE_BYTES) return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveEncryptionKey(keyHex), "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            }

            part.provider().toInputStream().use { input ->
                outputFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalCipherBytes = 0L
                    var totalPlainBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        totalCipherBytes += read.toLong()
                        if (totalCipherBytes > maxCipherBytes) {
                            throw IllegalArgumentException("Attachment too large")
                        }
                        val plainChunk = cipher.update(buffer, 0, read)
                        if (plainChunk != null && plainChunk.isNotEmpty()) {
                            totalPlainBytes += plainChunk.size.toLong()
                            if (totalPlainBytes > maxPlainBytes) {
                                throw IllegalArgumentException("Attachment too large")
                            }
                            output.write(plainChunk)
                        }
                    }

                    val finalChunk = cipher.doFinal()
                    if (finalChunk.isNotEmpty()) {
                        totalPlainBytes += finalChunk.size.toLong()
                        if (totalPlainBytes > maxPlainBytes) {
                            throw IllegalArgumentException("Attachment too large")
                        }
                        output.write(finalChunk)
                    }

                    StreamedAttachment(
                        totalInputBytes = totalCipherBytes,
                        plaintextBytes = totalPlainBytes,
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            null
        }
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

    private companion object {
        private const val TAG = "LomoShareServer"
        private const val MAX_TRANSFER_BODY_BYTES = 120L * 1024L * 1024L
        private const val MAX_MEMO_CHARS = 200_000
        private const val MAX_ATTACHMENTS = 20
        private const val MAX_STORAGE_FILENAME_CHARS = 96
        private const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
        private const val GCM_TAG_BYTES = 16L
        private const val MAX_ATTACHMENT_ENCRYPTED_SIZE_BYTES = MAX_ATTACHMENT_SIZE_BYTES + GCM_TAG_BYTES
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        private const val MAX_TOTAL_ATTACHMENT_ENCRYPTED_BYTES =
            MAX_TOTAL_ATTACHMENT_BYTES + (MAX_ATTACHMENTS * GCM_TAG_BYTES)
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val ENC_DOMAIN = "lomo-lan-share-enc-v1"
    }
}
