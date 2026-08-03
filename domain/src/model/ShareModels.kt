package com.lomo.domain.model

/**
 * Represents a discovered device on the LAN running Lomo.
 */
data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val trusted: Boolean = false,
)

/**
 * Info about an attachment to be transferred.
 */
data class ShareAttachmentInfo(
    val name: String,
    val type: String, // "image" | "audio"
    val size: Long,
)

data class LanTrustedPeer(
    val deviceId: String,
    val displayName: String,
    val pairedAtMs: Long,
)

data class LanPairingRequest(
    val pairingId: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val shortCode: String,
    val deadlineMs: Long,
)

data class LanIncomingBatch(
    val sessionId: String,
    val batchId: String,
    val senderDeviceId: String,
    val senderDisplayName: String,
    val itemCount: Int,
    val attachmentCount: Int,
    val totalBytes: Long,
    val titles: List<String>,
    val decision: LanBatchDecision,
    val items: List<LanReceivedItemResult>,
)

enum class LanBatchDecision {
    Pending,
    Approved,
    Rejected,
}

sealed interface LanReceivedItemResult {
    val itemId: String
    val itemIndex: Int

    data class Pending(
        override val itemId: String,
        override val itemIndex: Int,
    ) : LanReceivedItemResult

    data class Committed(
        override val itemId: String,
        override val itemIndex: Int,
        val memoId: String,
    ) : LanReceivedItemResult

    data class Failed(
        override val itemId: String,
        override val itemIndex: Int,
        val code: String,
    ) : LanReceivedItemResult
}

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

    data class WaitingPairing(
        val deviceName: String,
    ) : ShareTransferState

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
