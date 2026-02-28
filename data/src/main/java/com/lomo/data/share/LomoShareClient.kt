package com.lomo.data.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareAttachmentInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * HTTP client for sending memos to peer devices on the LAN.
 */
class LomoShareClient(
    private val context: Context,
    private val getPairingKeyHex: suspend () -> String?,
) {
    companion object {
        private const val TAG = "LomoShareClient"
        private const val MAX_ATTACHMENTS = 20
        private const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        private const val GCM_TAG_BYTES = 16L
        private const val MAX_TOTAL_ENCRYPTED_ATTACHMENT_BYTES = MAX_TOTAL_ATTACHMENT_BYTES + (MAX_ATTACHMENTS * GCM_TAG_BYTES)
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val client =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 70_000L // > 60s server timeout
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 70_000L
            }
        }

    data class PreparedSession(
        val sessionToken: String?,
        val keyHex: String?,
    )

    /**
     * Phase 1: Send prepare request and wait for receiver's decision.
     * @return session token when accepted, null when rejected/timeout.
     */
    suspend fun prepare(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        senderName: String,
        attachments: List<ShareAttachmentInfo>,
        e2eEnabled: Boolean,
    ): Result<PreparedSession> {
        return try {
            val attachmentNames = attachments.map { it.name.trim() }

            if (e2eEnabled) {
                val keyCandidates = ShareAuthUtils.resolveCandidateKeyHexes(getPairingKeyHex()?.trim())
                if (keyCandidates.isEmpty()) {
                    return Result.failure(Exception("LAN share pairing code is not configured"))
                }
            }

            // Step 0: Check connectivity with a quick ping (3s timeout)
            // If device is unreachable, fail fast instead of waiting 70s
            try {
                client.get("http://${device.host}:${device.port}/share/ping") {
                    timeout { requestTimeoutMillis = 3000L }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Connectivity check failed")
                return Result.failure(Exception("Device unreachable"))
            }

            if (!e2eEnabled) {
                val request =
                    buildPrepareRequest(
                        senderName = senderName,
                        content = content,
                        timestamp = timestamp,
                        attachments = attachments,
                        attachmentNames = attachmentNames,
                        e2eEnabled = false,
                        keyHex = null,
                    )
                val response =
                    client.post("http://${device.host}:${device.port}/share/prepare") {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(LomoShareServer.PrepareRequest.serializer(), request))
                    }

                if (response.status != HttpStatusCode.OK) {
                    val errorBody = response.bodyAsText()
                    val errorMessage = errorBody.ifBlank { "Prepare failed (${response.status.value})" }
                    return Result.failure(Exception(errorMessage))
                }

                val body = json.decodeFromString<LomoShareServer.PrepareResponse>(response.bodyAsText())
                Timber.tag(TAG).d("Prepare response: accepted=${body.accepted}, hasToken=${!body.sessionToken.isNullOrBlank()}")
                if (body.accepted && body.sessionToken.isNullOrBlank()) {
                    Result.failure(Exception("Invalid prepare response: missing session token"))
                } else {
                    Result.success(PreparedSession(sessionToken = body.sessionToken, keyHex = null))
                }
            } else {
                val keyCandidates = ShareAuthUtils.resolveCandidateKeyHexes(getPairingKeyHex()?.trim())
                var lastError: Exception? = null

                for ((index, keyHex) in keyCandidates.withIndex()) {
                    val request =
                        buildPrepareRequest(
                            senderName = senderName,
                            content = content,
                            timestamp = timestamp,
                            attachments = attachments,
                            attachmentNames = attachmentNames,
                            e2eEnabled = true,
                            keyHex = keyHex,
                        )

                    val response =
                        client.post("http://${device.host}:${device.port}/share/prepare") {
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(LomoShareServer.PrepareRequest.serializer(), request))
                        }
                    if (response.status == HttpStatusCode.OK) {
                        val body = json.decodeFromString<LomoShareServer.PrepareResponse>(response.bodyAsText())
                        Timber.tag(TAG).d("Prepare response: accepted=${body.accepted}, hasToken=${!body.sessionToken.isNullOrBlank()}")
                        if (body.accepted && body.sessionToken.isNullOrBlank()) {
                            return Result.failure(Exception("Invalid prepare response: missing session token"))
                        }
                        return Result.success(PreparedSession(sessionToken = body.sessionToken, keyHex = keyHex))
                    }

                    val errorBody = response.bodyAsText()
                    val errorMessage = errorBody.ifBlank { "Prepare failed (${response.status.value})" }
                    lastError = Exception(errorMessage)
                    val canRetryAuth = response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden
                    if (canRetryAuth && index < keyCandidates.lastIndex) {
                        Timber.tag(TAG).w("Prepare auth failed with key candidate #$index, retrying with compatibility key")
                        continue
                    }
                    return Result.failure(lastError)
                }

                Result.failure(lastError ?: Exception("Prepare failed"))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Prepare request failed")
            Result.failure(e)
        }
    }

    /**
     * Phase 2: Transfer memo content + attachments via multipart.
     * @param attachmentUris Map of filename to content URI for attachments within the memo.
     */
    suspend fun transfer(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        sessionToken: String,
        attachmentUris: Map<String, Uri>,
        e2eEnabled: Boolean,
        e2eKeyHex: String? = null,
    ): Boolean {
        val tempPayloadFiles = mutableListOf<File>()
        return try {
            data class TransferAttachment(
                val name: String,
                val nonceBase64: String?,
                val contentType: String,
                val payloadFile: File?,
                val sourceUri: Uri?,
                val payloadSizeBytes: Long?,
            )

            val attachmentPayloads = mutableListOf<TransferAttachment>()
            val payloadContent: String
            val contentNonce: String
            val authTimestampMs: Long
            val authNonce: String
            val authSignature: String

            if (attachmentUris.size > MAX_ATTACHMENTS) {
                throw IllegalArgumentException("Too many attachments")
            }

            if (e2eEnabled) {
                val keyHex =
                    ShareAuthUtils.resolvePrimaryKeyHex(e2eKeyHex)
                        ?: ShareAuthUtils.resolvePrimaryKeyHex(getPairingKeyHex()?.trim())
                if (keyHex == null) {
                    Timber.tag(TAG).w("LAN share pairing code is not configured")
                    return false
                }
                val encryptedContent =
                    ShareCryptoUtils.encryptText(
                        keyHex = keyHex,
                        plaintext = content,
                        aad = "memo-content",
                    )
                payloadContent = encryptedContent.ciphertextBase64
                contentNonce = encryptedContent.nonceBase64

                var totalEncryptedAttachmentBytes = 0L
                for ((filename, uri) in attachmentUris) {
                    val encrypted =
                        encryptUriToTempFile(
                            uri = uri,
                            keyHex = keyHex,
                            aad = "attachment:$filename",
                            maxBytes = MAX_ATTACHMENT_SIZE_BYTES,
                        ) ?: throw IllegalStateException("Failed to read attachment: $filename")
                    val tempPayload = encrypted.payloadFile
                    tempPayloadFiles += tempPayload
                    totalEncryptedAttachmentBytes += tempPayload.length()
                    if (totalEncryptedAttachmentBytes > MAX_TOTAL_ENCRYPTED_ATTACHMENT_BYTES) {
                        throw IllegalArgumentException("Attachments too large")
                    }
                    attachmentPayloads +=
                        TransferAttachment(
                            name = filename,
                            nonceBase64 = encrypted.nonceBase64,
                            contentType = guessContentType(filename),
                            payloadFile = tempPayload,
                            sourceUri = null,
                            payloadSizeBytes = tempPayload.length(),
                        )
                }

                authTimestampMs = System.currentTimeMillis()
                authNonce = ShareAuthUtils.generateNonce()
                val attachmentNames = attachmentPayloads.map { it.name }
                val signaturePayload =
                    ShareAuthUtils.buildTransferPayloadToSign(
                        sessionToken = sessionToken,
                        encryptedContent = payloadContent,
                        contentNonce = contentNonce,
                        timestamp = timestamp,
                        attachmentNames = attachmentNames,
                        authTimestampMs = authTimestampMs,
                        authNonce = authNonce,
                    )
                authSignature =
                    ShareAuthUtils.signPayloadHex(
                        keyHex = keyHex,
                        payload = signaturePayload,
                    )
            } else {
                payloadContent = content
                contentNonce = ""
                authTimestampMs = 0L
                authNonce = ""
                authSignature = ""

                var totalAttachmentBytes = 0L
                for ((filename, uri) in attachmentUris) {
                    val size = resolveUriSize(uri)
                    if (size != null) {
                        if (size > MAX_ATTACHMENT_SIZE_BYTES) {
                            throw IllegalArgumentException("Attachment too large")
                        }
                        totalAttachmentBytes += size
                        if (totalAttachmentBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                            throw IllegalArgumentException("Attachments too large")
                        }
                    }
                    attachmentPayloads +=
                        TransferAttachment(
                            name = filename,
                            nonceBase64 = null,
                            contentType = guessContentType(filename),
                            payloadFile = null,
                            sourceUri = uri,
                            payloadSizeBytes = size,
                        )
                }
            }

            val metadata =
                LomoShareServer.TransferMetadata(
                    sessionToken = sessionToken,
                    encryptedContent = payloadContent,
                    contentNonce = contentNonce,
                    timestamp = timestamp,
                    e2eEnabled = e2eEnabled,
                    attachmentNames = attachmentPayloads.map { it.name },
                    attachmentNonces =
                        attachmentPayloads
                            .mapNotNull { attachment ->
                                attachment.nonceBase64?.let { attachment.name to it }
                            }.toMap(),
                    authTimestampMs = authTimestampMs,
                    authNonce = authNonce,
                    authSignature = authSignature,
                )

            val response =
                client.submitFormWithBinaryData(
                    url = "http://${device.host}:${device.port}/share/transfer",
                    formData =
                        formData {
                            // Part 1: JSON metadata
                            append(
                                "metadata",
                                json.encodeToString(LomoShareServer.TransferMetadata.serializer(), metadata),
                            )

                            // Part 2+: Attachment files
                            var index = 0
                            for (attachment in attachmentPayloads) {
                                val headers =
                                    Headers.build {
                                        append(HttpHeaders.ContentType, attachment.contentType)
                                        append(HttpHeaders.ContentDisposition, "filename=\"${attachment.name}\"")
                                    }
                                appendInput(
                                    key = "attachment_$index",
                                    headers = headers,
                                    size = attachment.payloadSizeBytes,
                                ) {
                                    when {
                                        attachment.payloadFile != null -> {
                                            attachment.payloadFile
                                                .inputStream()
                                                .asSource()
                                                .buffered()
                                        }

                                        attachment.sourceUri != null -> {
                                            val input =
                                                context.contentResolver.openInputStream(attachment.sourceUri)
                                                    ?: throw IllegalStateException("Cannot open attachment: ${attachment.name}")
                                            input.asSource().buffered()
                                        }

                                        else -> {
                                            throw IllegalStateException("Attachment has no payload source: ${attachment.name}")
                                        }
                                    }
                                }
                                index++
                            }
                        },
                )

            if (response.status != HttpStatusCode.OK) {
                Timber.tag(TAG).w("Transfer failed with status=${response.status.value}: ${response.bodyAsText()}")
                return false
            }

            val body = json.decodeFromString<LomoShareServer.TransferResponse>(response.bodyAsText())
            Timber.tag(TAG).d("Transfer response: success=${body.success}")
            body.success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Transfer request failed")
            false
        } finally {
            tempPayloadFiles.forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    fun close() {
        client.close()
    }

    private fun buildPrepareRequest(
        senderName: String,
        content: String,
        timestamp: Long,
        attachments: List<ShareAttachmentInfo>,
        attachmentNames: List<String>,
        e2eEnabled: Boolean,
        keyHex: String?,
    ): LomoShareServer.PrepareRequest {
        val payloadContent: String
        val contentNonce: String
        val authTimestampMs: Long
        val authNonce: String
        val authSignature: String

        if (e2eEnabled) {
            val resolvedKey =
                keyHex
                    ?: throw IllegalArgumentException("Missing E2E key")
            val encryptedContent =
                ShareCryptoUtils.encryptText(
                    keyHex = resolvedKey,
                    plaintext = content,
                    aad = "memo-content",
                )
            payloadContent = encryptedContent.ciphertextBase64
            contentNonce = encryptedContent.nonceBase64
            authTimestampMs = System.currentTimeMillis()
            authNonce = ShareAuthUtils.generateNonce()
            val signaturePayload =
                ShareAuthUtils.buildPreparePayloadToSign(
                    senderName = senderName,
                    encryptedContent = payloadContent,
                    contentNonce = contentNonce,
                    timestamp = timestamp,
                    attachmentNames = attachmentNames,
                    authTimestampMs = authTimestampMs,
                    authNonce = authNonce,
                )
            authSignature =
                ShareAuthUtils.signPayloadHex(
                    keyHex = resolvedKey,
                    payload = signaturePayload,
                )
        } else {
            payloadContent = content
            contentNonce = ""
            authTimestampMs = 0L
            authNonce = ""
            authSignature = ""
        }

        return LomoShareServer.PrepareRequest(
            senderName = senderName,
            encryptedContent = payloadContent,
            contentNonce = contentNonce,
            timestamp = timestamp,
            e2eEnabled = e2eEnabled,
            attachments =
                attachments.map {
                    LomoShareServer.AttachmentInfo(name = it.name, type = it.type, size = it.size)
                },
            authTimestampMs = authTimestampMs,
            authNonce = authNonce,
            authSignature = authSignature,
        )
    }

    private fun resolveUriSize(uri: Uri): Long? {
        val queriedSize =
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index < 0 || cursor.isNull(index)) return@use null
                    cursor.getLong(index).takeIf { it >= 0L }
                }
            } catch (_: Exception) {
                null
            }
        if (queriedSize != null) return queriedSize

        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists() && file.isFile) {
                return file.length().takeIf { it >= 0L }
            }
        }

        return null
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
        return try {
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: return null
            input.use { source ->
                val nonce = ShareCryptoUtils.generateNonce()
                val cipher = ShareCryptoUtils.createEncryptCipher(keyHex = keyHex, nonce = nonce, aad = aad)

                val encryptedFile = File.createTempFile("share_payload_", ".bin", context.cacheDir)
                payloadFile = encryptedFile
                encryptedFile.outputStream().buffered().use { encryptedOut ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalPlainBytes = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        totalPlainBytes += read.toLong()
                        if (totalPlainBytes > maxBytes) {
                            throw IllegalArgumentException("Attachment too large")
                        }
                        val encryptedChunk = cipher.update(buffer, 0, read)
                        if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                            encryptedOut.write(encryptedChunk)
                        }
                    }
                    val finalChunk = cipher.doFinal()
                    if (finalChunk.isNotEmpty()) {
                        encryptedOut.write(finalChunk)
                    }
                }

                EncryptedTempPayload(
                    payloadFile = encryptedFile,
                    nonceBase64 = Base64.Default.encode(nonce),
                )
            }
        } catch (e: Exception) {
            payloadFile?.let { runCatching { it.delete() } }
            Timber.tag(TAG).e(e, "Failed to encrypt URI: $uri")
            null
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
}
