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
import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferErrorCode
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : LanShareService {
    private val _pendingPairing = MutableStateFlow<LanPairingRequest?>(null)
    private val _incomingBatch = MutableStateFlow<LanIncomingBatch?>(null)
    private val _trustedPeers = MutableStateFlow<List<LanTrustedPeer>>(emptyList())
    private val _transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
    private val _runtimeState = MutableStateFlow(LanShareRuntimeState.Stopped)
    private val _diagnostics = MutableStateFlow(LanShareDiscoveryDiagnostics())
    private val _startupFailures = MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)
    private val outgoingPayloads = ConcurrentHashMap<String, OutgoingPayload>()
    private val completedOutgoing = ConcurrentHashMap.newKeySet<String>()

    override val pendingPairing: StateFlow<LanPairingRequest?> = _pendingPairing.asStateFlow()
    override val incomingBatch: StateFlow<LanIncomingBatch?> = _incomingBatch.asStateFlow()
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
        appScope.launch(Dispatchers.IO) {
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
        appScope.launch(Dispatchers.IO) {
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
        appScope.launch(Dispatchers.IO) {
            if (preferences.lanShareEnabledValue()) {
                runtime.startDiscovery(lanShareDeviceName.value)
            }
        }
    }

    override fun stopDiscovery() = runtime.stopDiscovery()

    override fun refreshNetworkPermissionState() {
        appScope.launch(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
            try {
                check(preferences.lanShareEnabledValue()) { "LAN share is disabled in settings." }
                refreshPeers()
                if (_trustedPeers.value.none { peer -> peer.deviceId == device.deviceId }) {
                    val challenge = engine.beginLanPairing(device.deviceId, clockMillis(), PAIRING_TTL_MS)
                    _pendingPairing.value = challenge.toDomain()
                    _transferState.value = ShareTransferState.WaitingPairing(device.name)
                    return@withContext Result.success(Unit)
                }

                _transferState.value = ShareTransferState.Sending
                val session = engine.beginLanSession(device.deviceId, clockMillis(), SESSION_TTL_MS)
                engine.confirmLanSession(session.sessionId, deviceKey.sign(session), clockMillis())
                engine.lanSessionState(session.sessionId)

                val shape = engine.lanTransferShape()
                val attachments = attachmentUris.entries.mapIndexed { index, entry ->
                    prepareAttachment(index, entry.key, entry.value)
                }
                val contentBytes = content.encodeToByteArray()
                val batchId = randomId()
                engine.prepareLanBatch(
                    session.sessionId,
                    batchId,
                    listOf(
                        LanSendItemPlan(
                            timestampMs = timestamp,
                            contentDigest = contentBytes.sha256(),
                            contentBytes = contentBytes.size.toULong(),
                            title = content.lineSequence().firstOrNull()?.take(MAX_TITLE_CHARS).orEmpty(),
                            attachments = attachments.map(PreparedAttachment::plan),
                        ),
                    ),
                )
                outgoingPayloads[batchId] =
                    OutgoingPayload(session.sessionId, batchId, device.name, contentBytes, attachments, shape)
                _transferState.value = ShareTransferState.WaitingApproval(device.name)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _transferState.value = error.toTransferState(device.name)
                Result.failure(error)
            }
        }

    override fun confirmPairing(pairingId: String) {
        appScope.launch(Dispatchers.IO) {
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
        appScope.launch(Dispatchers.IO) {
            executeCommand {
                engine.declineLanPairing(pairingId)
                _pendingPairing.value = null
                _transferState.value = ShareTransferState.Idle
            }
        }
    }

    override fun approveIncoming(sessionId: String, batchId: String) {
        appScope.launch(Dispatchers.IO) {
            executeCommand { engine.approveLanBatch(sessionId, batchId, clockMillis(), APPROVAL_TTL_MS) }
        }
    }

    override fun rejectIncoming(sessionId: String, batchId: String) {
        appScope.launch(Dispatchers.IO) {
            executeCommand { engine.rejectLanBatch(sessionId, batchId, clockMillis()) }
        }
    }

    override fun revokePeer(deviceId: String) {
        appScope.launch(Dispatchers.IO) {
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
        _incomingBatch.value = inbox.batchRecoveries.firstOrNull()?.toDomain()
            ?: inbox.pendingBatches.firstOrNull()?.toDomain()
        refreshPeers()
        inbox.outgoingBatches.forEach { batch ->
            when (batch.phase) {
                LanOutgoingBatchPhase.AwaitingApproval -> Unit
                LanOutgoingBatchPhase.Approved -> transmitOnce(batch.batchId)
                LanOutgoingBatchPhase.Rejected -> {
                    outgoingPayloads.remove(batch.batchId)?.let { payload ->
                        _transferState.value =
                            ShareTransferState.Error(
                                ShareTransferError(
                                    ShareTransferErrorCode.TRANSFER_REJECTED,
                                    deviceName = payload.deviceName,
                                ),
                            )
                    }
                }
            }
        }
    }

    private fun transmitOnce(batchId: String) {
        if (!completedOutgoing.add(batchId)) return
        val payload = outgoingPayloads[batchId] ?: return
        appScope.launch(Dispatchers.IO) {
            try {
                _transferState.value = ShareTransferState.Transferring(0f)
                sendByteArray(payload, payload.shape.bodySlot, payload.content)
                payload.attachments.forEach { attachment ->
                    context.contentResolver.openInputStream(attachment.uri).required(attachment.uri).use { input ->
                        sendStream(payload, attachment.plan.slot, input, attachment.sizeBytes)
                    }
                }
                outgoingPayloads.remove(batchId)
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

    private fun sendByteArray(payload: OutgoingPayload, slot: UInt, bytes: ByteArray) {
        val width = payload.shape.chunkPlaintextBytes.toInt()
        val missing = engine.lanUnconfirmedBatchChunks(payload.batchId, 0u, slot).toHashSet()
        var index = 0
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + width, bytes.size)
            if (index.toUInt() in missing) {
                engine.sendLanBatchChunk(
                    payload.sessionId,
                    payload.batchId,
                    0u,
                    slot,
                    index.toUInt(),
                    bytes.copyOfRange(offset, end),
                )
            }
            offset = end
            index++
        }
    }

    private fun sendStream(payload: OutgoingPayload, slot: UInt, input: InputStream, sizeBytes: Long) {
        val width = payload.shape.chunkPlaintextBytes.toInt()
        val missing = engine.lanUnconfirmedBatchChunks(payload.batchId, 0u, slot).toHashSet()
        var remaining = sizeBytes
        var index = 0
        while (remaining > 0L) {
            val expected = minOf(width.toLong(), remaining).toInt()
            val chunk = input.readNBytes(expected)
            check(chunk.size == expected) { "LAN attachment source ended before its planned size" }
            if (index.toUInt() in missing) {
                engine.sendLanBatchChunk(
                    payload.sessionId,
                    payload.batchId,
                    0u,
                    slot,
                    index.toUInt(),
                    chunk,
                )
            }
            remaining -= expected
            index++
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
        val deviceName: String,
        val content: ByteArray,
        val attachments: List<PreparedAttachment>,
        val shape: LanTransferShape,
    )

    private companion object {
        const val APPROVAL_TTL_MS = 15 * 60 * 1_000L
        const val MAX_DEVICE_NAME_CHARS = 64
        const val MAX_TITLE_CHARS = 160
        const val PAIRING_TTL_MS = 2 * 60 * 1_000L
        const val SESSION_TTL_MS = 60 * 1_000L
        const val STREAM_BUFFER_BYTES = 64 * 1_024
    }
}

private suspend fun LomoLanSharePreferencesStore.lanShareEnabledValue(): Boolean =
    lanShareEnabled.first()

private fun resolvedDeviceName(stored: String?): String =
    stored?.trim()?.takeIf(String::isNotEmpty) ?: Build.MODEL.trim().takeIf(String::isNotEmpty) ?: "Android"

private fun LanPairingChallenge.toDomain() =
    LanPairingRequest(pairingId, peerDeviceId, peerDisplayName, shortCode, deadlineMs)

private fun LanPendingBatch.toDomain() =
    preview.toDomain(sessionId, LanBatchDecision.Pending, emptyList())

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
    )

private fun LanBatchPreview.toDomain(
    sessionId: String,
    decision: LanBatchDecision,
    items: List<LanReceivedItemResult>,
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
)

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()

private fun ByteArray.hex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun randomId(): String =
    ByteArray(RANDOM_ID_BYTES).also(SecureRandom()::nextBytes).hex()

private fun InputStream?.required(uri: Uri): InputStream =
    requireNotNull(this) { "LAN attachment source cannot be opened: $uri" }

private fun Exception.toTransferState(deviceName: String?): ShareTransferState.Error =
    ShareTransferState.Error(
        ShareTransferError(
            code = ShareTransferErrorCode.TRANSFER_FAILED,
            detail = message,
            deviceName = deviceName,
        ),
    )
