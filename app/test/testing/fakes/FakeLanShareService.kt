package com.lomo.app.testing.fakes

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.LanShareDiscoveryDiagnostics
import com.lomo.domain.model.LanShareRuntimeState
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeLanShareService : LanShareService {
    override val discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val incomingShare = MutableStateFlow<IncomingShareState>(IncomingShareState.None)
    override val transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
    override val lanShareRuntimeState = MutableStateFlow(LanShareRuntimeState.Stopped)
    override val lanShareDiscoveryDiagnostics = MutableStateFlow(LanShareDiscoveryDiagnostics())
    override val lanShareStartupFailures = MutableSharedFlow<LanShareStartupFailure>(extraBufferCapacity = 1)
    override val lanSharePairingCode = MutableStateFlow("")

    var lanShareEnabledValue = true
    override val lanShareEnabled: Flow<Boolean> get() = flowOf(lanShareEnabledValue)

    var lanShareE2eEnabledValue = true
    override val lanShareE2eEnabled: Flow<Boolean> get() = flowOf(lanShareE2eEnabledValue)

    var lanSharePairingConfiguredValue = true
    override val lanSharePairingConfigured: Flow<Boolean> get() = flowOf(lanSharePairingConfiguredValue)

    var lanShareDeviceNameValue = "Local"
    override val lanShareDeviceName: Flow<String> get() = flowOf(lanShareDeviceNameValue)

    // Call tracking
    var startServicesCalledCount = 0
    var stopServicesCalledCount = 0
    var startDiscoveryCalledCount = 0
    var stopDiscoveryCalledCount = 0
    var refreshNetworkPermissionStateCalledCount = 0
    var acceptIncomingCalledCount = 0
    var rejectIncomingCalledCount = 0
    var resetTransferStateCalledCount = 0

    // Sent memo tracking
    val sentMemos = mutableListOf<SentMemo>()
    data class SentMemo(
        val device: DiscoveredDevice,
        val content: String,
        val timestamp: Long,
        val attachmentUris: Map<String, String>
    )

    // Overrides/Stubs
    var sendMemoResult: Result<Unit> = Result.success(Unit)
    var requiresPairingValue = false
    var startDiscoveryError: Throwable? = null
    var setLanSharePairingCodeError: Throwable? = null
    var setLanShareE2eEnabledError: Throwable? = null
    var clearLanSharePairingCodeError: Throwable? = null
    var setLanShareDeviceNameError: Throwable? = null
    var resetTransferStateError: Throwable? = null

    override fun startServices() {
        startServicesCalledCount++
    }

    override fun stopServices() {
        stopServicesCalledCount++
    }

    override fun startDiscovery() {
        startDiscoveryCalledCount++
        startDiscoveryError?.let { throw it }
    }

    override fun stopDiscovery() {
        stopDiscoveryCalledCount++
    }

    override fun refreshNetworkPermissionState() {
        refreshNetworkPermissionStateCalledCount++
    }

    override suspend fun sendMemo(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        attachmentUris: Map<String, String>
    ): Result<Unit> {
        sentMemos.add(SentMemo(device, content, timestamp, attachmentUris))
        return sendMemoResult
    }

    override fun acceptIncoming() {
        acceptIncomingCalledCount++
    }

    override fun rejectIncoming() {
        rejectIncomingCalledCount++
    }

    override fun resetTransferState() {
        resetTransferStateCalledCount++
        resetTransferStateError?.let { throw it }
    }

    override suspend fun setLanShareEnabled(enabled: Boolean) {
        lanShareEnabledValue = enabled
    }

    override suspend fun setLanShareE2eEnabled(enabled: Boolean) {
        setLanShareE2eEnabledError?.let { throw it }
        lanShareE2eEnabledValue = enabled
    }

    override suspend fun setLanSharePairingCode(pairingCode: String) {
        setLanSharePairingCodeError?.let { throw it }
        lanSharePairingCode.value = pairingCode
    }

    override suspend fun clearLanSharePairingCode() {
        clearLanSharePairingCodeError?.let { throw it }
        lanSharePairingCode.value = ""
    }

    override suspend fun setLanShareDeviceName(deviceName: String) {
        setLanShareDeviceNameError?.let { throw it }
        lanShareDeviceNameValue = deviceName
    }

    override suspend fun requiresPairingBeforeSend(): Boolean {
        return requiresPairingValue
    }
}
