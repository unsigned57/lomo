package com.lomo.app.testing.fakes

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanIncomingBatch
import com.lomo.domain.model.LanPairingRequest
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import com.lomo.domain.model.LanShareRuntimeState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.LanTrustedPeer
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeLanShareService : LanShareService {
    override val discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val pendingPairing = MutableStateFlow<LanPairingRequest?>(null)
    override val incomingBatch = MutableStateFlow<LanIncomingBatch?>(null)
    override val trustedPeers = MutableStateFlow<List<LanTrustedPeer>>(emptyList())
    override val transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
    override val lanShareRuntimeState = MutableStateFlow(LanShareRuntimeState.Stopped)
    override val lanShareDiscoveryDiagnostics = MutableStateFlow(LanShareDiscoveryDiagnostics())
    override val lanShareStartupFailures = MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)

    var lanShareEnabledValue = true
    override val lanShareEnabled: Flow<Boolean> get() = flowOf(lanShareEnabledValue)

    var lanShareDeviceNameValue = "Local"
    override val lanShareDeviceName: Flow<String> get() = flowOf(lanShareDeviceNameValue)

    var startServicesCalledCount = 0
    var stopServicesCalledCount = 0
    var startDiscoveryCalledCount = 0
    var stopDiscoveryCalledCount = 0
    var refreshNetworkPermissionStateCalledCount = 0
    var resetTransferStateCalledCount = 0
    val confirmedPairings = mutableListOf<String>()
    val declinedPairings = mutableListOf<String>()
    val approvedBatches = mutableListOf<Pair<String, String>>()
    val rejectedBatches = mutableListOf<Pair<String, String>>()
    val revokedPeers = mutableListOf<String>()
    val sentMemos = mutableListOf<SentMemo>()

    var sendMemoResult: Result<Unit> = Result.success(Unit)
    var startDiscoveryError: Throwable? = null
    var setLanShareDeviceNameError: Throwable? = null
    var resetTransferStateError: Throwable? = null

    data class SentMemo(
        val device: DiscoveredDevice,
        val content: String,
        val timestamp: Long,
        val attachmentUris: Map<String, String>,
    )

    override fun startServices() {
        startServicesCalledCount += 1
    }

    override fun stopServices() {
        stopServicesCalledCount += 1
    }

    override fun startDiscovery() {
        startDiscoveryCalledCount += 1
        startDiscoveryError?.let { throw it }
    }

    override fun stopDiscovery() {
        stopDiscoveryCalledCount += 1
    }

    override fun refreshNetworkPermissionState() {
        refreshNetworkPermissionStateCalledCount += 1
    }

    override suspend fun sendMemo(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        attachmentUris: Map<String, String>,
    ): Result<Unit> {
        sentMemos += SentMemo(device, content, timestamp, attachmentUris)
        return sendMemoResult
    }

    override fun confirmPairing(pairingId: String) {
        confirmedPairings += pairingId
    }

    override fun declinePairing(pairingId: String) {
        declinedPairings += pairingId
    }

    override fun approveIncoming(sessionId: String, batchId: String) {
        approvedBatches += sessionId to batchId
    }

    override fun rejectIncoming(sessionId: String, batchId: String) {
        rejectedBatches += sessionId to batchId
    }

    override fun revokePeer(deviceId: String) {
        revokedPeers += deviceId
    }

    override fun resetTransferState() {
        resetTransferStateCalledCount += 1
        resetTransferStateError?.let { throw it }
    }

    override suspend fun setLanShareEnabled(enabled: Boolean) {
        lanShareEnabledValue = enabled
    }

    override suspend fun setLanShareDeviceName(deviceName: String) {
        setLanShareDeviceNameError?.let { throw it }
        lanShareDeviceNameValue = deviceName
    }
}
