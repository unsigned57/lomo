package com.lomo.data.share

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareAttachmentInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import timber.log.Timber

internal class SharePrepareRequestExecutor(
    private val client: HttpClient,
    private val json: Json,
    private val getPairingKeyHex: suspend () -> String?,
) {
    suspend fun prepare(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        senderName: String,
        attachments: List<ShareAttachmentInfo>,
        e2eEnabled: Boolean,
    ): Result<LomoShareClient.PreparedSession> =
        runCatching {
            val attachmentNames = attachments.map { it.name.trim() }
            ensureReachable(device)
            if (e2eEnabled) {
                prepareEncryptedSession(
                    device = device,
                    senderName = senderName,
                    content = content,
                    timestamp = timestamp,
                    attachments = attachments,
                    attachmentNames = attachmentNames,
                )
            } else {
                prepareOpenSession(
                    device = device,
                    senderName = senderName,
                    content = content,
                    timestamp = timestamp,
                    attachments = attachments,
                    attachmentNames = attachmentNames,
                )
            }
        }.onFailure { error ->
            Timber.tag(TAG).e(error, "Prepare request failed")
        }

    private suspend fun ensureReachable(device: DiscoveredDevice) {
        runNonFatalCatching {
            client.get("http://${device.host}:${device.port}/share/ping") {
                timeout { requestTimeoutMillis = LomoShareClient.PING_TIMEOUT_MS }
            }
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Connectivity check failed")
            throw SharePrepareRequestException("Device unreachable", error)
        }
    }

    private suspend fun prepareOpenSession(
        device: DiscoveredDevice,
        senderName: String,
        content: String,
        timestamp: Long,
        attachments: List<ShareAttachmentInfo>,
        attachmentNames: List<String>,
    ): LomoShareClient.PreparedSession {
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
        val response = submitPrepareRequest(device, request)
        return decodeSuccessfulSession(response.bodyText, keyHex = null)
    }

    private suspend fun prepareEncryptedSession(
        device: DiscoveredDevice,
        senderName: String,
        content: String,
        timestamp: Long,
        attachments: List<ShareAttachmentInfo>,
        attachmentNames: List<String>,
    ): LomoShareClient.PreparedSession {
        val keyCandidates = resolveCandidateKeyHexes(getPairingKeyHex()?.trim())
        require(keyCandidates.isNotEmpty()) {
            "LAN share pairing code is not configured"
        }

        var preparedSession: LomoShareClient.PreparedSession? = null
        var failure: Exception? = null
        var index = 0
        while (index <= keyCandidates.lastIndex && preparedSession == null) {
            val keyHex = keyCandidates[index]
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
            val response = submitPrepareRequest(device, request, allowFailure = true)
            when {
                response.status == HttpStatusCode.OK -> {
                    preparedSession = decodeSuccessfulSession(response.bodyText, keyHex)
                }

                shouldRetryAuth(response.status, index, keyCandidates.lastIndex) -> {
                    failure = SharePrepareRequestException(errorMessageFor(response.status, response.bodyText))
                    logRetryAttempt(index)
                }

                else -> {
                    failure = SharePrepareRequestException(errorMessageFor(response.status, response.bodyText))
                    index = keyCandidates.lastIndex
                }
            }
            index++
        }
        return preparedSession ?: throw (failure ?: SharePrepareRequestException("Prepare failed"))
    }

    private suspend fun submitPrepareRequest(
        device: DiscoveredDevice,
        request: LomoShareServer.PrepareRequest,
        allowFailure: Boolean = false,
    ): PrepareHttpResponse {
        val response =
            client.post("http://${device.host}:${device.port}/share/prepare") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(LomoShareServer.PrepareRequest.serializer(), request))
            }
        val bodyText = response.bodyAsText()
        if (!allowFailure && response.status != HttpStatusCode.OK) {
            throw SharePrepareRequestException(errorMessageFor(response.status, bodyText))
        }
        return PrepareHttpResponse(response.status, bodyText)
    }

    private fun decodeSuccessfulSession(
        bodyText: String,
        keyHex: String?,
    ): LomoShareClient.PreparedSession {
        val body = json.decodeFromString<LomoShareServer.PrepareResponse>(bodyText)
        Timber
            .tag(TAG)
            .d(
                "Prepare response: accepted=${body.accepted}, " +
                    "hasToken=${!body.sessionToken.isNullOrBlank()}",
            )
        require(!(body.accepted && body.sessionToken.isNullOrBlank())) {
            "Invalid prepare response: missing session token"
        }
        return LomoShareClient.PreparedSession(
            sessionToken = body.sessionToken,
            keyHex = keyHex,
        )
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
        val authPayload =
            if (e2eEnabled) {
                buildEncryptedPreparePayload(
                    senderName = senderName,
                    content = content,
                    timestamp = timestamp,
                    attachmentNames = attachmentNames,
                    keyHex = requireNotNull(keyHex) { "Missing E2E key" },
                )
            } else {
                PrepareAuthPayload(
                    encryptedContent = content,
                    contentNonce = "",
                    authTimestampMs = 0L,
                    authNonce = "",
                    authSignature = "",
                )
            }
        return LomoShareServer.PrepareRequest(
            senderName = senderName,
            encryptedContent = authPayload.encryptedContent,
            contentNonce = authPayload.contentNonce,
            timestamp = timestamp,
            e2eEnabled = e2eEnabled,
            attachments =
                attachments.map {
                    LomoShareServer.AttachmentInfo(name = it.name, type = it.type, size = it.size)
                },
            authTimestampMs = authPayload.authTimestampMs,
            authNonce = authPayload.authNonce,
            authSignature = authPayload.authSignature,
        )
    }

    private fun buildEncryptedPreparePayload(
        senderName: String,
        content: String,
        timestamp: Long,
        attachmentNames: List<String>,
        keyHex: String,
    ): PrepareAuthPayload {
        val encryptedContent =
            ShareCryptoUtils.encryptText(
                keyHex = keyHex,
                plaintext = content,
                aad = MEMO_CONTENT_AAD,
            )
        val authTimestampMs = System.currentTimeMillis()
        val authNonce = ShareAuthUtils.generateNonce()
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
        return PrepareAuthPayload(
            encryptedContent = encryptedContent.ciphertextBase64,
            contentNonce = encryptedContent.nonceBase64,
            authTimestampMs = authTimestampMs,
            authNonce = authNonce,
            authSignature = ShareAuthUtils.signPayloadHex(keyHex = keyHex, payload = signaturePayload),
        )
    }

    private fun shouldRetryAuth(
        status: HttpStatusCode,
        index: Int,
        lastIndex: Int,
    ): Boolean =
        (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) &&
            index < lastIndex

    private fun logRetryAttempt(index: Int) {
        Timber
            .tag(TAG)
            .w(
                "Prepare auth failed with key candidate #$index, " +
                    "retrying with compatibility key",
            )
    }

    private fun errorMessageFor(
        status: HttpStatusCode,
        bodyText: String,
    ): String = bodyText.ifBlank { "Prepare failed (${status.value})" }

    private data class PrepareHttpResponse(
        val status: HttpStatusCode,
        val bodyText: String,
    )

    private data class PrepareAuthPayload(
        val encryptedContent: String,
        val contentNonce: String,
        val authTimestampMs: Long,
        val authNonce: String,
        val authSignature: String,
    )

    private class SharePrepareRequestException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private companion object {
        private const val MEMO_CONTENT_AAD = "memo-content"
        private const val TAG = "LomoShareClient"
    }
}
