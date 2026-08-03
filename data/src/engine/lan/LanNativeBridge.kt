package com.lomo.data.engine.lan

/** Android network fact accepted by the Rust LAN owner. */
internal data class LanBindCandidate(
    val host: String,
    val port: UInt,
)

/** Monotonic Android network snapshot. */
internal data class LanNetworkFacts(
    val revision: ULong,
    val localNetworkPermissionGranted: Boolean,
    val candidates: List<LanBindCandidate>,
)

/** One NSD endpoint. Rust validates identity, address and protocol version. */
internal data class LanDiscoveredPeer(
    val deviceId: String,
    val displayName: String,
    val host: String,
    val port: UInt,
    val protocolVersion: UInt,
)

/** Monotonic Android discovery snapshot. */
internal data class LanDiscoveryFacts(
    val revision: ULong,
    val peers: List<LanDiscoveredPeer>,
)

internal enum class LanServicePhase {
    Stopped,
    Listening,
}

internal data class LanServiceState(
    val phase: LanServicePhase,
    val listenAddress: String?,
)

internal data class LanTransferShape(
    val bodySlot: UInt,
    val chunkPlaintextBytes: UInt,
)

/** Public half of the non-exportable Android Keystore device identity. */
internal data class LanDeviceIdentity(
    val publicKey: ByteArray,
    val displayName: String,
)

internal data class LanLocalIdentity(
    val deviceId: String,
    val displayName: String,
)

/** Transcript facts displayed and signed by the platform. */
internal interface LanSigningChallenge {
    val transcriptToSign: ByteArray
}

internal data class LanPairingChallenge(
    val pairingId: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val shortCode: String,
    override val transcriptToSign: ByteArray,
    val deadlineMs: Long,
) : LanSigningChallenge

internal data class LanSessionChallenge(
    val sessionId: String,
    val peerDeviceId: String,
    override val transcriptToSign: ByteArray,
    val deadlineMs: Long,
) : LanSigningChallenge

internal enum class LanSessionPhase {
    Authenticated,
}

internal data class LanSessionState(
    val sessionId: String,
    val peerDeviceId: String,
    val phase: LanSessionPhase,
)

internal data class LanPeer(
    val deviceId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val pairedAtMs: Long,
    val revoked: Boolean,
    val revokedAtMs: Long?,
)

internal data class LanPeerPage(
    val peers: List<LanPeer>,
    val total: UInt,
)

internal data class LanAttachmentPlan(
    val slot: UInt,
    val sourceReference: String,
    val name: String,
    val digest: String,
    val sizeBytes: ULong,
)

internal data class LanSendItemPlan(
    val timestampMs: Long,
    val contentDigest: String,
    val contentBytes: ULong,
    val title: String,
    val attachments: List<LanAttachmentPlan>,
)

/** Body-free metadata shown before a receiver approves a batch. */
internal data class LanBatchPreview(
    val batchId: String,
    val senderDeviceId: String,
    val senderDisplayName: String,
    val itemCount: UInt,
    val attachmentCount: UInt,
    val totalBytes: ULong,
    val titles: List<String>,
)

internal data class LanPendingBatch(
    val sessionId: String,
    val preview: LanBatchPreview,
)

internal enum class LanReceivedBatchDecision {
    Pending,
    Approved,
    Rejected,
}

internal sealed interface LanReceivedItemRecovery {
    val itemId: String
    val itemIndex: UInt

    data class Pending(
        override val itemId: String,
        override val itemIndex: UInt,
    ) : LanReceivedItemRecovery

    data class Committed(
        override val itemId: String,
        override val itemIndex: UInt,
        val memoId: String,
    ) : LanReceivedItemRecovery

    data class Failed(
        override val itemId: String,
        override val itemIndex: UInt,
        val code: String,
    ) : LanReceivedItemRecovery
}

internal data class LanBatchRecovery(
    val sessionId: String,
    val preview: LanBatchPreview,
    val decision: LanReceivedBatchDecision,
    val items: List<LanReceivedItemRecovery>,
)

internal enum class LanOutgoingBatchPhase {
    AwaitingApproval,
    Approved,
    Rejected,
}

internal data class LanOutgoingBatch(
    val batchId: String,
    val phase: LanOutgoingBatchPhase,
)

internal data class LanCommittableItem(
    val batchId: String,
    val itemIndex: UInt,
)

/** Live signing work and durable approval work derived from the Rust runtime. */
internal data class LanRuntimeInbox(
    val pairingChallenges: List<LanPairingChallenge>,
    val sessionChallenges: List<LanSessionChallenge>,
    val activeSessions: List<LanSessionState>,
    val pendingBatches: List<LanPendingBatch>,
    val batchRecoveries: List<LanBatchRecovery>,
    val committableItems: List<LanCommittableItem>,
    val outgoingBatches: List<LanOutgoingBatch>,
)

/**
 * Installation-level LAN capability on the sole managed engine handle.
 *
 * Implementations convert platform facts and delegate every protocol/state decision to Rust.
 */
internal interface LanRuntimeNativeBridge {
    fun lanTransferShape(): LanTransferShape

    fun updateLanNetworkSnapshot(snapshot: LanNetworkFacts)

    fun updateLanDiscoverySnapshot(snapshot: LanDiscoveryFacts)

    fun startLanService(): LanServiceState

    fun stopLanService(): LanServiceState

    fun listLanDiscoveredPeers(): List<LanDiscoveredPeer>

    fun configureLanIdentity(identity: LanDeviceIdentity): LanLocalIdentity

    fun pollLanListener(nowMs: Long): LanRuntimeInbox

    fun lanRuntimeInbox(): LanRuntimeInbox
}

internal interface LanPairingNativeBridge {

    fun beginLanPairing(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanPairingChallenge

    fun lanPairingChallenge(pairingId: String): LanPairingChallenge

    fun confirmLanPairing(
        pairingId: String,
        signature: ByteArray,
        nowMs: Long,
    )

    fun declineLanPairing(pairingId: String)

    fun beginLanSession(
        peerDeviceId: String,
        nowMs: Long,
        ttlMs: Long,
    ): LanSessionChallenge

    fun lanSessionChallenge(sessionId: String): LanSessionChallenge

    fun confirmLanSession(
        sessionId: String,
        signature: ByteArray,
        nowMs: Long,
    )

    fun lanSessionState(sessionId: String): LanSessionState
}

internal interface LanTransferNativeBridge {

    fun prepareLanBatch(
        sessionId: String,
        batchId: String,
        items: List<LanSendItemPlan>,
    )

    fun lanBatchPreview(batchId: String): LanBatchPreview

    fun approveLanBatch(
        sessionId: String,
        batchId: String,
        nowMs: Long,
        ttlMs: Long,
    )

    fun rejectLanBatch(
        sessionId: String,
        batchId: String,
        rejectedAtMs: Long,
    )

    fun sendLanBatchChunk(
        sessionId: String,
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
        chunkIndex: UInt,
        plaintext: ByteArray,
    )

    fun lanUnconfirmedBatchChunks(
        batchId: String,
        itemIndex: UInt,
        attachmentSlot: UInt,
    ): List<UInt>

    fun commitReceivedLanItem(
        batchId: String,
        itemIndex: UInt,
        nowMs: Long,
    ): String

    fun listLanPeers(): LanPeerPage

    fun revokeLanPeer(
        deviceId: String,
        revokedAtMs: Long,
    ): LanPeerPage
}

internal interface LanNativeBridge :
    LanRuntimeNativeBridge,
    LanPairingNativeBridge,
    LanTransferNativeBridge
