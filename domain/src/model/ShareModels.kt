package com.lomo.domain.model

/**
 * Represents a discovered device on the LAN running Lomo.
 */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Info about an attachment to be transferred.
 */
data class ShareAttachmentInfo(
    val name: String,
    val type: String, // "image" | "audio"
    val size: Long,
)

/**
 * The payload sent when sharing a memo over LAN.
 */
data class SharePayload(
    val content: String,
    val timestamp: Long,
    val senderName: String,
    val attachments: List<ShareAttachmentInfo> = emptyList(),
)

/**
 * State of an outgoing share transfer (sender side).
 */
enum class ShareTransferErrorCode {
    PAIRING_REQUIRED,
    ATTACHMENT_RESOLVE_FAILED,
    TOO_MANY_ATTACHMENTS,
    ATTACHMENT_TOO_LARGE,
    ATTACHMENTS_TOO_LARGE,
    UNSUPPORTED_ATTACHMENT_TYPE,
    CONNECTION_FAILED,
    TRANSFER_REJECTED,
    TRANSFER_FAILED,
    UNKNOWN,
}

data class ShareTransferError(
    val code: ShareTransferErrorCode,
    val detail: String? = null,
    val deviceName: String? = null,
    val missingAttachmentCount: Int? = null,
)

sealed interface ShareTransferState {
    data object Idle : ShareTransferState

    data object Sending : ShareTransferState

    data class WaitingApproval(
        val deviceName: String,
    ) : ShareTransferState

    data class Transferring(
        val progress: Float,
    ) : ShareTransferState

    data class Success(
        val deviceName: String,
    ) : ShareTransferState

    data class Error(
        val error: ShareTransferError,
    ) : ShareTransferState
}

/**
 * State of an incoming share request (receiver side).
 */
sealed interface IncomingShareState {
    data object None : IncomingShareState

    data class Pending(
        val payload: SharePayload,
    ) : IncomingShareState
}
