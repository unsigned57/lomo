package com.lomo.data.share

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.SharePayload
import com.lomo.domain.model.ShareTransferLimits
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import timber.log.Timber

internal class SharePrepareRequestProcessor(
    private val json: Json,
    private val isE2eEnabled: suspend () -> Boolean,
    private val validatePrepareRequest: (LomoShareServer.PrepareRequest) -> String?,
    private val validatePrepareAuthentication: suspend (LomoShareServer.PrepareRequest) -> ShareAuthValidation,
    private val onIncomingPrepare: () -> ((SharePayload) -> Unit)?,
    private val reserveApproval: (CompletableDeferred<Boolean>) -> Boolean,
    private val storeApprovedSession:
        (
            requestHash: String,
            attachmentNames: Set<String>,
            e2eEnabled: Boolean,
        ) -> String,
    private val clearPendingApproval: () -> Unit,
    private val buildRequestHash: (
        content: String,
        timestamp: Long,
        attachmentNames: List<String>,
        e2eEnabled: Boolean,
    ) -> String,
    private val approvalTimeoutMs: Long,
) {
    suspend fun handle(call: ApplicationCall) {
        try {
            val error =
                runNonFatalCatching {
                    val request = parseRequest(call)
                    val validatedRequest = validateRequest(request)
                    val payload = request.toSharePayload(validatedRequest.decryptedContent)
                    Timber.tag(TAG).d("Prepare request from: ${payload.senderName}")

                    val decision = awaitApproval(payload)
                    val response =
                        buildPrepareResponse(
                            accepted = decision.accepted,
                            requestHash = validatedRequest.requestHash,
                            attachmentNames = validatedRequest.attachmentNames,
                            e2eEnabled = request.e2eEnabled,
                        )
                    call.respondText(
                        text = json.encodeToString(LomoShareServer.PrepareResponse.serializer(), response),
                        contentType = ContentType.Application.Json,
                        status = decision.status,
                    )
                }.exceptionOrNull()
            when (error) {
                null -> Unit
                is PrepareRejectedException -> call.respond(error.status, error.message)
                else -> {
                    Timber.tag(TAG).e(error, "Error handling prepare request")
                    call.respond(HttpStatusCode.InternalServerError, "Error")
                }
            }
        } finally {
            clearPendingApproval()
        }
    }

    private suspend fun parseRequest(call: ApplicationCall): LomoShareServer.PrepareRequest {
        ensurePrepareContentLength(call)
        val body = call.receiveText()
        ensurePrepareBodyLength(body)
        return json.decodeFromString(body)
    }

    private suspend fun validateRequest(request: LomoShareServer.PrepareRequest): ValidatedPrepareRequest {
        if (request.e2eEnabled != isE2eEnabled()) {
            rejectPrepare(HttpStatusCode.PreconditionFailed, "Encryption mode mismatch")
        }
        if (!request.e2eEnabled) {
            Timber.tag(TAG).w("Received OPEN mode prepare request (unauthenticated)")
        }

        validatePrepareRequest(request)?.let { error ->
            rejectPrepare(HttpStatusCode.BadRequest, error)
        }

        val authValidation = validatePrepareAuthentication(request)
        if (!authValidation.ok) {
            rejectPrepare(authValidation.status, authValidation.message)
        }
        if (request.e2eEnabled && authValidation.keyHex.isNullOrBlank()) {
            rejectPrepare(HttpStatusCode.PreconditionFailed, "Missing pairing key")
        }

        val decryptedContent = decryptPrepareContent(request, authValidation.keyHex)
        if (decryptedContent.length > ShareTransferLimits.maxMemoChars(e2eEnabled = false)) {
            rejectPrepare(HttpStatusCode.PayloadTooLarge, "Memo content too large")
        }

        val normalizedNames = request.attachments.map { it.name.trim() }
        return ValidatedPrepareRequest(
            decryptedContent = decryptedContent,
            attachmentNames = normalizedNames,
            requestHash =
                buildRequestHash(
                    decryptedContent,
                    request.timestamp,
                    normalizedNames,
                    request.e2eEnabled,
                ),
        )
    }

    private fun decryptPrepareContent(
        request: LomoShareServer.PrepareRequest,
        keyHex: String?,
    ): String =
        if (request.e2eEnabled) {
            ShareCryptoUtils.decryptText(
                keyHex = keyHex.orEmpty(),
                ciphertextBase64 = request.encryptedContent,
                nonceBase64 = request.contentNonce,
                aad = MEMO_CONTENT_AAD,
            ) ?: rejectPrepare(HttpStatusCode.Unauthorized, "Cannot decrypt prepare content")
        } else {
            request.encryptedContent
        }

    private suspend fun awaitApproval(payload: SharePayload): PrepareDecision {
        val deferred = CompletableDeferred<Boolean>()
        if (!reserveApproval(deferred)) {
            return PrepareDecision(status = HttpStatusCode.TooManyRequests, accepted = false)
        }

        onIncomingPrepare()?.invoke(payload)
        val accepted =
            try {
                withTimeout(approvalTimeoutMs) {
                    deferred.await()
                }
            } catch (_: TimeoutCancellationException) {
                Timber.tag(TAG).w("Approval timed out")
                false
            }
        return PrepareDecision(status = HttpStatusCode.OK, accepted = accepted)
    }

    private fun buildPrepareResponse(
        accepted: Boolean,
        requestHash: String,
        attachmentNames: List<String>,
        e2eEnabled: Boolean,
    ): LomoShareServer.PrepareResponse {
        val sessionToken =
            accepted.takeIf { it }?.let {
                storeApprovedSession(
                    requestHash,
                    attachmentNames.toSet(),
                    e2eEnabled,
                )
            }
        return LomoShareServer.PrepareResponse(accepted = accepted, sessionToken = sessionToken)
    }

    private data class ValidatedPrepareRequest(
        val decryptedContent: String,
        val attachmentNames: List<String>,
        val requestHash: String,
    )

    private data class PrepareDecision(
        val status: HttpStatusCode,
        val accepted: Boolean,
    )

    private companion object {
        private const val MEMO_CONTENT_AAD = "memo-content"
        private const val TAG = "LomoShareServer"
    }
}

private fun ensurePrepareContentLength(call: ApplicationCall) {
    val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > ShareTransferLimits.MAX_PREPARE_BODY_CHARS) {
        rejectPrepare(HttpStatusCode.PayloadTooLarge, "Prepare payload too large")
    }
}

private fun ensurePrepareBodyLength(body: String) {
    if (body.length > ShareTransferLimits.MAX_PREPARE_BODY_CHARS) {
        rejectPrepare(HttpStatusCode.PayloadTooLarge, "Prepare payload too large")
    }
}

private fun rejectPrepare(
    status: HttpStatusCode,
    message: String,
): Nothing = throw PrepareRejectedException(status, message)

private class PrepareRejectedException(
    val status: HttpStatusCode,
    override val message: String,
) : IllegalStateException(message)
