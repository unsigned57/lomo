package com.lomo.domain.repository

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanIncomingBatch
import com.lomo.domain.model.LanPairingRequest
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import com.lomo.domain.model.LanShareRuntimeState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.LanTrustedPeer
import com.lomo.domain.model.ShareTransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain interface for LAN share operations: device discovery, memo transfer, and configuration.
 */
interface LanShareStateRepository {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val pendingPairing: StateFlow<LanPairingRequest?>
    val incomingBatch: StateFlow<LanIncomingBatch?>
    val trustedPeers: StateFlow<List<LanTrustedPeer>>
    val transferState: StateFlow<ShareTransferState>
    val lanShareRuntimeState: StateFlow<LanShareRuntimeState>
    val lanShareDiscoveryDiagnostics: StateFlow<LanShareDiscoveryDiagnostics>
    val lanShareStartupFailures: Flow<LanShareStartupFailure>
    val lanShareEnabled: Flow<Boolean>
    val lanShareDeviceName: Flow<String>
}

interface LanShareLifecycleController {
    fun startServices()

    fun stopServices()

    fun startDiscovery()

    fun stopDiscovery()

    fun refreshNetworkPermissionState()
}

interface LanShareTransferController {
    suspend fun sendMemo(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        attachmentUris: Map<String, String>,
    ): Result<Unit>

    fun confirmPairing(pairingId: String)

    fun declinePairing(pairingId: String)

    fun approveIncoming(
        sessionId: String,
        batchId: String,
    )

    fun rejectIncoming(
        sessionId: String,
        batchId: String,
    )

    fun revokePeer(deviceId: String)

    fun resetTransferState()
}

interface LanShareConfigurationController {
    suspend fun setLanShareEnabled(enabled: Boolean)

    suspend fun setLanShareDeviceName(deviceName: String)
}

interface LanShareService :
    LanShareStateRepository,
    LanShareLifecycleController,
    LanShareTransferController,
    LanShareConfigurationController
