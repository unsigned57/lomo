package com.lomo.data.engine.lan

import android.content.Context
import android.net.Uri
import android.os.Build
import com.lomo.data.engine.ManagedEngineSession
import com.lomo.data.local.datastore.LomoLanSharePreferencesStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanBatchDecision
import com.lomo.domain.model.LanIncomingBatch
import com.lomo.domain.model.LanPairingRequest
import com.lomo.domain.model.LanReceivedItemResult
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import com.lomo.domain.model.LanShareRuntimeState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.LanTrustedPeer
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.ShareTransferErrorPolicy
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private const val RANDOM_ID_BYTES = 16

/**
 * Sole production LAN adapter. Protocol and durable state stay in [ManagedEngineSession]; this
 * class only supplies Android byte streams, Keystore signatures, preferences and UI projections.
 */
internal class RustLanShareService(
    private val context: Context,
    private val preferences: LomoLanSharePreferencesStore,
    private val engine: ManagedEngineSession,
    private val runtime: LanRuntimeCoordinator,
    private val deviceKey: LanDeviceKey,
    private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : LanShareService {
    private val _pendingPairing = MutableStateFlow<LanPairingRequest?>(null)
    private val _incomingBatches = MutableStateFlow<List<LanIncomingBatch>>(emptyList())
    private val _trustedPeers = MutableStateFlow<List<LanTrustedPeer>>(emptyList())
    private val _transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
    private val _runtimeState = MutableStateFlow(LanShareRuntimeState.Stopped)
    private val _diagnostics = MutableStateFlow(LanShareDiscoveryDiagnostics())
    private val _startupFailures = MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)
    private val outgoingPayloads = ConcurrentHashMap<String, OutgoingPayload>()
    private val completedOutgoing = ConcurrentHashMap.newKeySet<String>()
    private val rebindingOutgoing = ConcurrentHashMap.newKeySet<String>()

    override val pendingPairing: StateFlow<LanPairingRequest?> = _pendingPairing.asStateFlow()
    override val incomingBatches: StateFlow<List<LanIncomingBatch>> = _incomingBatches.asStateFlow()
    override val trustedPeers: StateFlow<List<LanTrustedPeer>> = _trustedPeers.asStateFlow()
    override val transferState: StateFlow<ShareTransferState> = _transferState.asStateFlow()
    override val lanShareRuntimeState: StateFlow<LanShareRuntimeState> = _runtimeState.asStateFlow()
    override val lanShareDiscoveryDiagnostics: StateFlow<LanShareDiscoveryDiagnostics> = _diagnostics.asStateFlow()
    override val lanShareStartupFailures: Flow<LanShareStartupFailure> = _startupFailures.asSharedFlow()
    override val lanShareEnabled: Flow<Boolean> = preferences.lanShareEnabled
    override val lanShareDeviceName: StateFlow<String> =
        preferences.lanShareDeviceName
            .map(::resolvedDeviceName)
            .stateIn(appScope, SharingStarted.Eagerly, resolvedDeviceName(null))

    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> =
        runtime.discoveredPeers
            .map { peers ->
                val trustedIds = _trustedPeers.value.mapTo(HashSet(), LanTrustedPeer::deviceId)
                peers.map { peer ->
                    DiscoveredDevice(
                        deviceId = peer.deviceId,
                        name = peer.displayName,
                        host = peer.host,
                        port = peer.port.toInt(),
                        trusted = peer.deviceId in trustedIds,
                    )
                }
            }
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    init {
        appScope.launch(dispatcherProvider.io) {
            runtime.inbox.collect(::publishInbox)
        }
        appScope.launch {
            runtime.serviceState.collect { service ->
                publishRuntimeState(
                    if (service.phase == LanServicePhase.Listening) {
                        LanShareRuntimeState.Running
                    } else {
                        LanShareRuntimeState.Stopped
                    },
                )
            }
        }
        appScope.launch {
            runtime.failure.collect { failure ->
                if (failure != null) {
                    val state =
                        when (failure.operation) {
                            LanRuntimeFailureOperation.Permission -> LanShareRuntimeState.PermissionBlocked
                            LanRuntimeFailureOperation.Topology -> LanShareRuntimeState.WaitingForTopology
                            else -> LanShareRuntimeState.Stopped
                        }
                    publishRuntimeState(state)
                    val startup =
                        if (failure.operation == LanRuntimeFailureOperation.Discovery) {
                            LanShareStartupFailure.DiscoveryStartFailed
                        } else {
                            LanShareStartupFailure.ServiceRegistrationFailed
                        }
                    _startupFailures.tryEmit(startup)
                }
            }
        }
    }

    override fun startServices() {
        appScope.launch(dispatcherProvider.io) {
            if (preferences.lanShareEnabledValue()) {
                runtime.startServices(lanShareDeviceName.value)
                refreshPeers()
            }
        }
    }

    override fun stopServices() {
        runtime.stopServices()
        publishRuntimeState(LanShareRuntimeState.Stopped)
    }

    override fun startDiscovery() {
        appScope.launch(dispatcherProvider.io) {
            if (preferences.lanShareEnabledValue()) {
                runtime.startDiscovery(lanShareDeviceName.value)
            }
        }
    }

    override fun stopDiscovery() = runtime.stopDiscovery()

    override fun refreshNetworkPermissionState() {
        appScope.launch(dispatcherProvider.io) {
            if (preferences.lanShareEnabledValue()) {
                runtime.startServices(lanShareDeviceName.value)
                runtime.startDiscovery(lanShareDeviceName.value)
            }
        }
    }

    override suspend fun sendMemo(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        attachmentUris: Map<String, String>,
    ): Result<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                check(preferences.lanShareEnabledValue()) { "LAN share is disabled in settings." }
                refreshPeers()
                if (_trustedPeers.value.none { peer -> peer.deviceId == device.deviceId }) {
                    val limits = engine.lanProtocolLimits()
                    val challenge = engine.beginLanPairing(device.deviceId, clockMillis(), limits.pairingTtlMs)
                    _pendingPairing.value = challenge.toDomain()
                    _transferState.value = ShareTransferState.WaitingPairing(device.name)
                    return@withContext Result.success(Unit)
                }

                _transferState.value = ShareTransferState.Sending
                val limits = engine.lanProtocolLimits()
                val session = engine.beginLanSession(device.deviceId, clockMillis(), limits.sessionTtlMs)
                engine.confirmLanSession(session.sessionId, deviceKey.sign(session), clockMillis())
                engine.lanSessionState(session.sessionId)

                val shape = engine.lanTransferShape()
                val attachments = attachmentUris.entries.mapIndexed { index, entry ->
                    prepareAttachment(index, entry.key, entry.value)
                }
                val contentBytes = content.encodeToByteArray()
                val batchId = randomId()
                val itemPlan =
                    LanSendItemPlan(
                        timestampMs = timestamp,
                        contentDigest = contentBytes.sha256(),
                        contentBytes = contentBytes.size.toULong(),
                        title = content.lineSequence().firstOrNull()?.take(MAX_TITLE_CHARS).orEmpty(),
                        attachments = attachments.map(PreparedAttachment::plan),
                    )
                engine.prepareLanBatch(session.sessionId, batchId, listOf(itemPlan))
                outgoingPayloads[batchId] =
                    OutgoingPayload(
                        session.sessionId,
                        batchId,
                        device.deviceId,
                        device.name,
                        contentBytes,
                        attachments,
                        shape,
                        itemPlan,
                    )
                _transferState.value = ShareTransferState.WaitingApproval(device.name)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _transferState.value = error.toTransferState(device.name)
                Result.failure(error)
            }
        }

    override fun confirmPairing(pairingId: String) {
        appScope.launch(dispatcherProvider.io) {
            executeCommand {
                val challenge = engine.lanPairingChallenge(pairingId)
                engine.confirmLanPairing(pairingId, deviceKey.sign(challenge), clockMillis())
                _pendingPairing.value = null
                refreshPeers()
                _transferState.value = ShareTransferState.Idle
            }
        }
    }

    override fun declinePairing(pairingId: String) {
        appScope.launch(dispatcherProvider.io) {
            executeCommand {
                engine.declineLanPairing(pairingId)
                _pendingPairing.value = null
                _transferState.value = ShareTransferState.Idle
            }
        }
    }

    override fun approveIncoming(sessionId: String, batchId: String) {
        appScope.launch(dispatcherProvider.io) {
            executeCommand {
                engine.approveLanBatch(
                    sessionId,
                    batchId,
                    clockMillis(),
                    engine.lanProtocolLimits().approvalTtlMs,
                )
            }
        }
    }

    override fun rejectIncoming(sessionId: String, batchId: String) {
        appScope.launch(dispatcherProvider.io) {
            executeCommand { engine.rejectLanBatch(sessionId, batchId, clockMillis()) }
        }
    }

    override fun revokePeer(deviceId: String) {
        appScope.launch(dispatcherProvider.io) {
            executeCommand {
                engine.revokeLanPeer(deviceId, clockMillis())
                refreshPeers()
            }
        }
    }

    override fun resetTransferState() {
        _transferState.value = ShareTransferState.Idle
    }

    override suspend fun setLanShareEnabled(enabled: Boolean) {
        preferences.updateLanShareEnabled(enabled)
        if (enabled) {
            runtime.startServices(lanShareDeviceName.value)
        } else {
            runtime.stopServices()
        }
    }

    override suspend fun setLanShareDeviceName(deviceName: String) {
        val normalized = deviceName.filterNot(Char::isISOControl).trim().take(MAX_DEVICE_NAME_CHARS)
        preferences.updateLanShareDeviceName(normalized)
        runtime.stopServices()
        runtime.startServices(resolvedDeviceName(normalized.ifEmpty { null }))
    }

    private fun publishInbox(inbox: LanRuntimeInbox) {
        val challenge = inbox.pairingChallenges.firstOrNull()
        if (challenge != null) _pendingPairing.value = challenge.toDomain()
        _incomingBatches.value =
            inbox.batchRecoveries
                .filter { recovery ->
                    recovery.drive != LanReceivedBatchDrive.Rejected &&
                        recovery.drive != LanReceivedBatchDrive.Complete &&
                        recovery.drive != LanReceivedBatchDrive.ApprovalExpired
                }.map(LanBatchRecovery::toDomain) +
                inbox.pendingBatches.map(LanPendingBatch::toDomain)
        refreshPeers()
        inbox.outgoingBatches.forEach { batch ->
            when (batch.drive) {
                LanOutgoingBatchDrive.AwaitingDecision,
                LanOutgoingBatchDrive.Complete,
                -> Unit
                LanOutgoingBatchDrive.Sendable -> {
                    publishOutgoingProgress(batch)
                    transmitOnce(batch.batchId)
                }
                LanOutgoingBatchDrive.AwaitingReport -> publishOutgoingProgress(batch)
                LanOutgoingBatchDrive.NeedsRebind -> rebindOutgoing(batch.batchId)
                LanOutgoingBatchDrive.Rejected,
                LanOutgoingBatchDrive.Failed,
                -> {
                    completedOutgoing.remove(batch.batchId)
                    val failedPayload = outgoingPayloads.remove(batch.batchId)
                    if (failedPayload != null) {
                        val failureCode = batch.failureCode
                        _transferState.value =
                            ShareTransferState.Error(
                                if (failureCode != null) {
                                    ShareTransferErrorPolicy.refusal(failureCode, failedPayload.deviceName)
                                } else {
                                    ShareTransferErrorPolicy.transferRejected(failedPayload.deviceName)
                                },
                            )
                    }
                }
            }
        }
    }

    /**
     * Rebinds a suspended outgoing batch to a fresh session: the durable batch id and the exact
     * same item plan are re-prepared so the receiver sees recovery, never a new batch. The
     * rebound session id is written back before [completedOutgoing] is cleared so the next
     * Sendable drive resumes transmission of only the unconfirmed chunks.
     */
    private fun rebindOutgoing(batchId: String) {
        val payload = outgoingPayloads[batchId] ?: return
        if (!rebindingOutgoing.add(batchId)) return
        appScope.launch(dispatcherProvider.io) {
            try {
                val limits = engine.lanProtocolLimits()
                val session =
                    engine.beginLanSession(payload.deviceId, clockMillis(), limits.sessionTtlMs)
                engine.confirmLanSession(session.sessionId, deviceKey.sign(session), clockMillis())
                engine.prepareLanBatch(session.sessionId, payload.batchId, listOf(payload.plan))
                outgoingPayloads[batchId] = payload.copy(sessionId = session.sessionId)
                completedOutgoing.remove(batchId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _transferState.value = error.toTransferState(payload.deviceName)
            } finally {
                rebindingOutgoing.remove(batchId)
            }
        }
    }

    /** Progress is a durable fact: confirmed bytes over planned bytes, never wire traffic. */
    private fun publishOutgoingProgress(batch: LanOutgoingBatch) {
        if (!outgoingPayloads.containsKey(batch.batchId)) return
        if (batch.totalBytes == 0uL) return
        _transferState.value =
            ShareTransferState.Transferring(
                (batch.confirmedBytes.toFloat() / batch.totalBytes.toFloat()).coerceIn(0f, 1f),
            )
    }

    private fun transmitOnce(batchId: String) {
        if (!completedOutgoing.add(batchId)) return
        val payload = outgoingPayloads[batchId] ?: return
        appScope.launch(dispatcherProvider.io) {
            try {
                chunkSender.sendByteArray(payload, payload.shape.bodySlot, payload.content)
                payload.attachments.forEach { attachment ->
                    context.contentResolver.openInputStream(attachment.uri).required(attachment.uri).use { input ->
                        chunkSender.sendStream(payload, attachment.plan.slot, input, attachment.sizeBytes)
                    }
                }
                outgoingPayloads.remove(batchId)
                // Completion is receipt-driven: send only returns after every ACK is durable.
                _transferState.value = ShareTransferState.Success(payload.deviceName)
            } catch (error: CancellationException) {
                completedOutgoing.remove(batchId)
                throw error
            } catch (error: Exception) {
                completedOutgoing.remove(batchId)
                _transferState.value = error.toTransferState(payload.deviceName)
            }
        }
    }

    /** Chunks outbound payloads through the Rust-owned in-flight window. */
    private val chunkSender = LanChunkSender()

    private inner class LanChunkSender {
        fun sendByteArray(
            payload: OutgoingPayload,
            slot: UInt,
            bytes: ByteArray,
        ) {
            val width = payload.shape.chunkPlaintextBytes.toInt()
            val missing = engine.lanUnconfirmedBatchChunks(payload.batchId, 0u, slot).toHashSet()
            val group = ArrayList<LanChunkSend>(payload.shape.maxInflightChunks.toInt())
            var index = 0
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + width, bytes.size)
                if (index.toUInt() in missing) {
                    group.add(
                        LanChunkSend(
                            payload.sessionId,
                            payload.batchId,
                            0u,
                            slot,
                            index.toUInt(),
                            bytes.copyOfRange(offset, end),
                        ),
                    )
                    flushChunkGroup(payload, group)
                }
                offset = end
                index++
            }
            drainChunkGroup(group)
        }

        fun sendStream(
            payload: OutgoingPayload,
            slot: UInt,
            input: InputStream,
            sizeBytes: Long,
        ) {
            val width = payload.shape.chunkPlaintextBytes.toInt()
            val missing = engine.lanUnconfirmedBatchChunks(payload.batchId, 0u, slot).toHashSet()
            val group = ArrayList<LanChunkSend>(payload.shape.maxInflightChunks.toInt())
            var remaining = sizeBytes
            var index = 0
            while (remaining > 0L) {
                val expected = minOf(width.toLong(), remaining).toInt()
                val chunk = input.readNBytes(expected)
                check(chunk.size == expected) { "LAN attachment source ended before its planned size" }
                if (index.toUInt() in missing) {
                    group.add(
                        LanChunkSend(
                            payload.sessionId,
                            payload.batchId,
                            0u,
                            slot,
                            index.toUInt(),
                            chunk,
                        ),
                    )
                    flushChunkGroup(payload, group)
                }
                remaining -= expected
                index++
            }
            drainChunkGroup(group)
        }

        /** Sends the group the moment it reaches the Rust-owned in-flight window. */
        private fun flushChunkGroup(
            payload: OutgoingPayload,
            group: ArrayList<LanChunkSend>,
        ) {
            if (group.size >= payload.shape.maxInflightChunks.toInt()) {
                drainChunkGroup(group)
            }
        }

        private fun drainChunkGroup(group: ArrayList<LanChunkSend>) {
            if (group.isEmpty()) return
            engine.sendLanBatchChunks(group.toList())
            group.clear()
        }
    }

    private fun prepareAttachment(index: Int, reference: String, rawUri: String): PreparedAttachment {
        val uri = Uri.parse(rawUri)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(uri).required(uri).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var read = input.read(buffer)
            while (read >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    size += read
                }
                read = input.read(buffer)
            }
        }
        return PreparedAttachment(
            uri = uri,
            sizeBytes = size,
            plan =
                LanAttachmentPlan(
                    index.toUInt(),
                    reference,
                    reference.substringAfterLast('/'),
                    digest.digest().hex(),
                    size.toULong(),
                ),
        )
    }

    private fun refreshPeers() {
        _trustedPeers.value =
            engine.listLanPeers().peers
                .filterNot(LanPeer::revoked)
                .map { peer -> LanTrustedPeer(peer.deviceId, peer.displayName, peer.pairedAtMs) }
    }

    private inline fun executeCommand(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            _transferState.value = error.toTransferState(null)
        }
    }

    private fun publishRuntimeState(state: LanShareRuntimeState) {
        _runtimeState.value = state
        _diagnostics.value = LanShareDiscoveryDiagnostics(runtimeState = state)
    }

    private data class PreparedAttachment(
        val uri: Uri,
        val sizeBytes: Long,
        val plan: LanAttachmentPlan,
    )

    private data class OutgoingPayload(
        val sessionId: String,
        val batchId: String,
        val deviceId: String,
        val deviceName: String,
        val content: ByteArray,
        val attachments: List<PreparedAttachment>,
        val shape: LanTransferShape,
        /** The durable batch plan: re-prepared verbatim on session rebind, never rebuilt. */
        val plan: LanSendItemPlan,
    )

    private companion object {
        const val MAX_DEVICE_NAME_CHARS = 64
        const val MAX_TITLE_CHARS = 160
        const val STREAM_BUFFER_BYTES = 64 * 1_024
    }
}

private suspend fun LomoLanSharePreferencesStore.lanShareEnabledValue(): Boolean =
    lanShareEnabled.first()

private fun resolvedDeviceName(stored: String?): String {
    val storedName = stored?.trim()
    if (!storedName.isNullOrEmpty()) return storedName
    return Build.MODEL.trim().ifEmpty { "Android" }
}

private fun LanPairingChallenge.toDomain() =
    LanPairingRequest(pairingId, peerDeviceId, peerDisplayName, shortCode, deadlineMs)

private fun LanPendingBatch.toDomain() =
    preview.toDomain(sessionId, LanBatchDecision.Pending, emptyList(), 0uL)

private fun LanBatchRecovery.toDomain() =
    preview.toDomain(
        sessionId,
        when (decision) {
            LanReceivedBatchDecision.Pending -> LanBatchDecision.Pending
            LanReceivedBatchDecision.Approved -> LanBatchDecision.Approved
            LanReceivedBatchDecision.Rejected -> LanBatchDecision.Rejected
        },
        items.map { item ->
            when (item) {
                is LanReceivedItemRecovery.Pending ->
                    LanReceivedItemResult.Pending(item.itemId, item.itemIndex.toInt())
                is LanReceivedItemRecovery.Committed ->
                    LanReceivedItemResult.Committed(item.itemId, item.itemIndex.toInt(), item.memoId)
                is LanReceivedItemRecovery.Failed ->
                    LanReceivedItemResult.Failed(item.itemId, item.itemIndex.toInt(), item.code)
            }
        },
        confirmedBytes,
    )

private fun LanBatchPreview.toDomain(
    sessionId: String,
    decision: LanBatchDecision,
    items: List<LanReceivedItemResult>,
    confirmedBytes: ULong,
) = LanIncomingBatch(
    sessionId = sessionId,
    batchId = batchId,
    senderDeviceId = senderDeviceId,
    senderDisplayName = senderDisplayName,
    itemCount = itemCount.toInt(),
    attachmentCount = attachmentCount.toInt(),
    totalBytes = totalBytes.toLong(),
    titles = titles,
    decision = decision,
    items = items,
    confirmedBytes = confirmedBytes.toLong(),
)

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()

private fun ByteArray.hex(): String =
    joinToString(separator = "") { byte -> "%02x".format(java.util.Locale.ROOT, byte) }

private fun randomId(): String =
    ByteArray(RANDOM_ID_BYTES).also(SecureRandom()::nextBytes).hex()

private fun InputStream?.required(uri: Uri): InputStream =
    requireNotNull(this) { "LAN attachment source cannot be opened: $uri" }

private fun Exception.toTransferState(deviceName: String?): ShareTransferState.Error =
    ShareTransferState.Error(
        when (this) {
            is EngineCommandFailureException ->
                ShareTransferErrorPolicy.fromEngineFailure(failure, deviceName)
            else -> ShareTransferErrorPolicy.protocolFailed(deviceName, message)
        },
    )
