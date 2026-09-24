package com.lomo.domain.model

object ShareTransferErrorPolicy {
    private const val LAN_BATCH_REJECTED = "lan_batch_rejected"
    private const val LAN_PEER_REVOKED = "lan_peer_revoked"
    private const val LAN_APPROVAL_EXPIRED = "lan_approval_expired"
    private const val LAN_APPROVAL_INVALID = "lan_approval_invalid"
    private const val LAN_APPROVAL_PREFIX = "lan_approval"
    private const val LAN_PEER_PREFIX = "lan_peer"
    private const val LAN_PAIRING_PREFIX = "lan_pairing"

    fun pairingRequiredBeforeSend(): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.PAIRING_REQUIRED,
        )

    fun missingAttachments(missingCount: Int): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.ATTACHMENT_RESOLVE_FAILED,
            missingAttachmentCount = missingCount,
        )

    fun tooManyAttachments(): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.TOO_MANY_ATTACHMENTS,
        )

    fun attachmentTooLarge(): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.ATTACHMENT_TOO_LARGE,
        )

    fun attachmentsTooLarge(): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.ATTACHMENTS_TOO_LARGE,
        )

    fun unsupportedAttachmentType(): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.UNSUPPORTED_ATTACHMENT_TYPE,
        )

    fun connectionFailed(detail: String?): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.CONNECTION_FAILED,
            detail = detail?.trim(),
        )

    fun transferRejected(deviceName: String): ShareTransferError =
        ShareTransferError(code = ShareTransferErrorCode.TRANSFER_REJECTED, deviceName = deviceName)

    /**
     * Maps one structured engine rejection to its presentation bucket without dropping the wire
     * code or retry disposition. Categories and codes the domain cannot classify land on the
     * generic [ShareTransferErrorCode.PROTOCOL_FAILED] bucket with the diagnostic intact.
     */
    fun fromEngineFailure(
        failure: EngineCommandFailure,
        deviceName: String?,
    ): ShareTransferError {
        val bucket =
            when (failure.category) {
                EngineFailureCategory.NETWORK,
                EngineFailureCategory.TIMEOUT,
                EngineFailureCategory.BUSY,
                -> ShareTransferErrorCode.CONNECTION_FAILED
                EngineFailureCategory.STORAGE,
                EngineFailureCategory.CORRUPTION,
                -> ShareTransferErrorCode.STORAGE_FAILED
                EngineFailureCategory.CANCELLED -> ShareTransferErrorCode.TRANSFER_CANCELLED
                EngineFailureCategory.AUTHENTICATION ->
                    if (failure.code == LAN_PEER_REVOKED) {
                        ShareTransferErrorCode.PEER_REVOKED
                    } else {
                        ShareTransferErrorCode.AUTHENTICATION_FAILED
                    }
                EngineFailureCategory.PERMISSION ->
                    when (failure.code) {
                        LAN_BATCH_REJECTED -> ShareTransferErrorCode.TRANSFER_REJECTED
                        LAN_APPROVAL_EXPIRED,
                        LAN_APPROVAL_INVALID,
                        -> ShareTransferErrorCode.APPROVAL_EXPIRED
                        else -> ShareTransferErrorCode.PROTOCOL_FAILED
                    }
                EngineFailureCategory.VALIDATION,
                EngineFailureCategory.RESOURCE_LIMIT,
                EngineFailureCategory.CONFLICT,
                EngineFailureCategory.INTERNAL,
                -> ShareTransferErrorCode.PROTOCOL_FAILED
            }
        return ShareTransferError(
            code = bucket,
            detail = failure.diagnostic.trim().ifEmpty { null },
            deviceName = deviceName,
            engineCode = failure.code,
            retryDisposition = failure.retryDisposition,
        )
    }

    /**
     * Maps a peer refusal code carried on a terminal outgoing-batch drive. The code is protocol
     * vocabulary owned by the LAN journal; unrecognised codes stay diagnosable via [engineCode].
     */
    fun refusal(
        engineCode: String,
        deviceName: String?,
    ): ShareTransferError =
        ShareTransferError(
            code =
                when {
                    engineCode == LAN_BATCH_REJECTED -> ShareTransferErrorCode.TRANSFER_REJECTED
                    engineCode == LAN_PEER_REVOKED -> ShareTransferErrorCode.PEER_REVOKED
                    engineCode.startsWith(LAN_APPROVAL_PREFIX) -> ShareTransferErrorCode.APPROVAL_EXPIRED
                    engineCode.startsWith(LAN_PEER_PREFIX) ||
                        engineCode.startsWith(LAN_PAIRING_PREFIX)
                    -> ShareTransferErrorCode.PAIRING_REQUIRED
                    else -> ShareTransferErrorCode.PROTOCOL_FAILED
                },
            detail = engineCode,
            deviceName = deviceName,
            engineCode = engineCode,
        )

    /** Generic local failure: the exception carries no engine vocabulary, only its message. */
    fun protocolFailed(
        deviceName: String?,
        detail: String?,
    ): ShareTransferError =
        ShareTransferError(
            code = ShareTransferErrorCode.PROTOCOL_FAILED,
            detail = detail?.trim(),
            deviceName = deviceName,
        )
}
