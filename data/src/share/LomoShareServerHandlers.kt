package com.lomo.data.share

import com.lomo.domain.model.SharePayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal fun createLomoShareTransferHandler(
    json: Json,
    requestValidator: ShareRequestValidator,
    authValidator: ShareAuthenticationValidator,
    isE2eEnabled: suspend () -> Boolean,
    getPairingKeyHex: suspend () -> String?,
    consumeApprovedSession: (String, String, Set<String>, Boolean) -> Boolean,
    buildRequestHash: (String, Long, List<String>, Boolean) -> String,
    onSaveAttachment: () -> ShareAttachmentSaver?,
    onDeleteAttachment: () -> ShareAttachmentRollback?,
    onSaveMemo: () -> ShareMemoSaver?,
): LomoShareTransferHandler =
    LomoShareTransferHandler(
        json = json,
        isE2eEnabled = isE2eEnabled,
        validateTransferMetadata = requestValidator::validateTransferMetadata,
        validateTransferAuthentication = { metadata ->
            authValidator.validateTransferAuthentication(metadata, getPairingKeyHex)
        },
        consumeApprovedSession = consumeApprovedSession,
        buildRequestHash = buildRequestHash,
        onSaveAttachment = onSaveAttachment,
        onDeleteAttachment = onDeleteAttachment,
        onSaveMemo = onSaveMemo,
    )

internal fun createSharePrepareHandler(
    json: Json,
    requestValidator: ShareRequestValidator,
    authValidator: ShareAuthenticationValidator,
    isE2eEnabled: suspend () -> Boolean,
    getPairingKeyHex: suspend () -> String?,
    onIncomingPrepare: () -> ((SharePayload) -> Unit)?,
    reserveApproval: (CompletableDeferred<Boolean>) -> Boolean,
    storeApprovedSession: (String, Set<String>, Boolean) -> String,
    clearPendingApproval: () -> Unit,
    buildRequestHash: (String, Long, List<String>, Boolean) -> String,
    approvalTimeoutMs: Long,
): SharePrepareRequestProcessor =
    SharePrepareRequestProcessor(
        json = json,
        isE2eEnabled = isE2eEnabled,
        validatePrepareRequest = requestValidator::validatePrepareRequest,
        validatePrepareAuthentication = { request ->
            authValidator.validatePrepareAuthentication(request, getPairingKeyHex)
        },
        onIncomingPrepare = onIncomingPrepare,
        reserveApproval = reserveApproval,
        storeApprovedSession = storeApprovedSession,
        clearPendingApproval = clearPendingApproval,
        buildRequestHash = buildRequestHash,
        approvalTimeoutMs = approvalTimeoutMs,
    )

internal fun buildShareRequestHash(
    content: String,
    timestamp: Long,
    attachmentNames: List<String>,
    e2eEnabled: Boolean,
): String {
    val canonicalNames = attachmentNames.map { it.trim() }.sorted()
    val raw =
        buildString {
            append(if (e2eEnabled) "e2e" else "open")
            append('\n')
            append(timestamp)
            append('\n')
            append(content)
            append('\n')
            canonicalNames.forEach { append(it).append('\n') }
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
