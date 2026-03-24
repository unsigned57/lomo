package com.lomo.domain.repository

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.ShareTransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain interface for LAN share operations: device discovery, memo transfer, and configuration.
 */
interface LanShareStateRepository {
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    val incomingShare: StateFlow<IncomingShareState>
    val transferState: StateFlow<ShareTransferState>
    val lanSharePairingCode: StateFlow<String>
    val lanShareE2eEnabled: Flow<Boolean>
    val lanSharePairingConfigured: Flow<Boolean>
    val lanShareDeviceName: Flow<String>
}

interface LanShareLifecycleController {
    fun startServices()

    fun stopServices()

    fun startDiscovery()

    fun stopDiscovery()
}

interface LanShareTransferController {
    suspend fun sendMemo(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        attachmentUris: Map<String, String>,
    ): Result<Unit>

    fun acceptIncoming()

    fun rejectIncoming()

    fun resetTransferState()
}

interface LanShareConfigurationController {
    suspend fun setLanShareE2eEnabled(enabled: Boolean)

    suspend fun setLanSharePairingCode(pairingCode: String)

    suspend fun clearLanSharePairingCode()

    suspend fun setLanShareDeviceName(deviceName: String)

    suspend fun requiresPairingBeforeSend(): Boolean
}

interface LanShareService :
    LanShareStateRepository,
    LanShareLifecycleController,
    LanShareTransferController,
    LanShareConfigurationController
