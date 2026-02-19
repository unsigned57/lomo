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
sealed interface ShareTransferState {
    data object Idle : ShareTransferState
    data object Sending : ShareTransferState
    data class WaitingApproval(val deviceName: String) : ShareTransferState
    data class Transferring(val progress: Float) : ShareTransferState
    data class Success(val deviceName: String) : ShareTransferState
    data class Error(val message: String) : ShareTransferState
}

/**
 * State of an incoming share request (receiver side).
 */
sealed interface IncomingShareState {
    data object None : IncomingShareState
    data class Pending(val payload: SharePayload) : IncomingShareState
}
