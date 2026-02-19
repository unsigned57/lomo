package com.lomo.data.share

import android.content.Context
import android.net.Uri
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareAttachmentInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * HTTP client for sending memos to peer devices on the LAN.
 */
class LomoShareClient(
    private val context: Context,
    private val getPairingKeyHex: suspend () -> String?,
) {
    companion object {
        private const val TAG = "LomoShareClient"
        private const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 70_000L // > 60s server timeout
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 70_000L
        }
    }

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
    ): Result<String?> {
        return try {
            val pairingKeyHex = getPairingKeyHex()?.trim()
            if (!ShareAuthUtils.isValidKeyHex(pairingKeyHex)) {
                return Result.failure(Exception("LAN share pairing code is not configured"))
            }
            val keyHex = pairingKeyHex ?: return Result.failure(Exception("LAN share pairing code is not configured"))

            val authTimestampMs = System.currentTimeMillis()
            val authNonce = ShareAuthUtils.generateNonce()
            val attachmentNames = attachments.map { it.name.trim() }
            val encryptedContent =
                ShareCryptoUtils.encryptText(
                    keyHex = keyHex,
                    plaintext = content,
                    aad = "memo-content",
                )
            val signaturePayload =
                ShareAuthUtils.buildPreparePayloadToSign(
                    senderName = senderName,
                    encryptedContent = encryptedContent.ciphertextBase64,
                    contentNonce = encryptedContent.nonceBase64,
                    timestamp = timestamp,
                    attachmentNames = attachmentNames,
                    authTimestampMs = authTimestampMs,
                    authNonce = authNonce,
                )
            val authSignature =
                ShareAuthUtils.signPayloadHex(
                    keyHex = keyHex,
                    payload = signaturePayload,
                )

            val request = LomoShareServer.PrepareRequest(
                senderName = senderName,
                encryptedContent = encryptedContent.ciphertextBase64,
                contentNonce = encryptedContent.nonceBase64,
                timestamp = timestamp,
                attachments = attachments.map {
                    LomoShareServer.AttachmentInfo(name = it.name, type = it.type, size = it.size)
                },
                authTimestampMs = authTimestampMs,
                authNonce = authNonce,
                authSignature = authSignature,
            )

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

            val response = client.post("http://${device.host}:${device.port}/share/prepare") {
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
                Result.success(body.sessionToken)
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
    ): Boolean {
        return try {
            val pairingKeyHex = getPairingKeyHex()?.trim()
            if (!ShareAuthUtils.isValidKeyHex(pairingKeyHex)) {
                Timber.tag(TAG).w("LAN share pairing code is not configured")
                return false
            }
            val keyHex = pairingKeyHex ?: return false

            val authTimestampMs = System.currentTimeMillis()
            val authNonce = ShareAuthUtils.generateNonce()
            data class EncryptedAttachment(
                val name: String,
                val encryptedBytes: ByteArray,
                val nonceBase64: String,
                val contentType: String,
            )

            val encryptedContent =
                ShareCryptoUtils.encryptText(
                    keyHex = keyHex,
                    plaintext = content,
                    aad = "memo-content",
                )
            val encryptedAttachments = mutableListOf<EncryptedAttachment>()
            for ((filename, uri) in attachmentUris) {
                val bytes = readUriBytes(uri, MAX_ATTACHMENT_SIZE_BYTES)
                    ?: throw IllegalStateException("Failed to read attachment: $filename")
                val encrypted =
                    ShareCryptoUtils.encryptBytes(
                        keyHex = keyHex,
                        plaintext = bytes,
                        aad = "attachment:$filename",
                    )
                encryptedAttachments +=
                    EncryptedAttachment(
                        name = filename,
                        encryptedBytes = encrypted.ciphertext,
                        nonceBase64 = encrypted.nonceBase64,
                        contentType = guessContentType(filename),
                    )
            }

            val attachmentNames = encryptedAttachments.map { it.name }
            val signaturePayload =
                ShareAuthUtils.buildTransferPayloadToSign(
                    sessionToken = sessionToken,
                    encryptedContent = encryptedContent.ciphertextBase64,
                    contentNonce = encryptedContent.nonceBase64,
                    timestamp = timestamp,
                    attachmentNames = attachmentNames,
                    authTimestampMs = authTimestampMs,
                    authNonce = authNonce,
                )
            val authSignature =
                ShareAuthUtils.signPayloadHex(
                    keyHex = keyHex,
                    payload = signaturePayload,
                )

            val metadata = LomoShareServer.TransferMetadata(
                sessionToken = sessionToken,
                encryptedContent = encryptedContent.ciphertextBase64,
                contentNonce = encryptedContent.nonceBase64,
                timestamp = timestamp,
                attachmentNames = attachmentNames,
                attachmentNonces = encryptedAttachments.associate { it.name to it.nonceBase64 },
                authTimestampMs = authTimestampMs,
                authNonce = authNonce,
                authSignature = authSignature,
            )

            val response = client.submitFormWithBinaryData(
                url = "http://${device.host}:${device.port}/share/transfer",
                formData = formData {
                    // Part 1: JSON metadata
                    append(
                        "metadata",
                        json.encodeToString(LomoShareServer.TransferMetadata.serializer(), metadata),
                    )

                    // Part 2+: Attachment files
                    var index = 0
                    for (attachment in encryptedAttachments) {
                        append(
                            "attachment_$index",
                            attachment.encryptedBytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, attachment.contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${attachment.name}\"")
                            },
                        )
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
        }
    }

    fun close() {
        client.close()
    }

    private fun readUriBytes(uri: Uri, maxBytes: Long): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
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
                output.toByteArray()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read URI: $uri")
            null
        }
    }

    private fun guessContentType(filename: String): String {
        return when {
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
}
