package com.lomo.data.share

import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates LAN share collaborators.
 *
 * Responsibilities are split across dedicated collaborators:
 * - lifecycle: [ShareServiceLifecycleController]
 * - pairing/config policy: [SharePairingConfig]
 * - transfer orchestration: [ShareTransferOrchestrator]
 * - attachment storage strategy: [ShareAttachmentStorage]
 * - incoming memo persistence: [ShareIncomingMemoSaver]
 */
@Singleton
class ShareServiceManager
    @Inject
    constructor(
        private val lifecycleController: ShareServiceLifecycleController,
        private val transferOrchestrator: ShareTransferOrchestrator,
        private val pairingConfig: SharePairingConfig,
        private val attachmentStorage: ShareAttachmentStorage,
        private val incomingMemoSaver: ShareIncomingMemoSaver,
    ) : LanShareService {
        override val discoveredDevices: StateFlow<List<DiscoveredDevice>> =
            lifecycleController.discoveredDevices

        private val _incomingShare = MutableStateFlow<IncomingShareState>(IncomingShareState.None)
        override val incomingShare: StateFlow<IncomingShareState> = _incomingShare.asStateFlow()

        override val transferState: StateFlow<ShareTransferState> = transferOrchestrator.transferState

        override val lanShareE2eEnabled = pairingConfig.lanShareE2eEnabled
        override val lanSharePairingConfigured = pairingConfig.lanSharePairingConfigured
        override val lanSharePairingCode = pairingConfig.lanSharePairingCode
        override val lanShareDeviceName = pairingConfig.lanShareDeviceName

        init {
            lifecycleController.bindServerCallbacks(
                onIncomingPrepare = { payload ->
                    _incomingShare.value = IncomingShareState.Pending(payload)
                },
                onSaveAttachment = { name, type, payloadFile ->
                    attachmentStorage.saveAttachmentFile(name, type, payloadFile)
                },
                onSaveMemo = { content, timestamp, attachmentMappings ->
                    incomingMemoSaver.saveReceivedMemo(content, timestamp, attachmentMappings)
                },
            )
        }

        override fun startServices() {
            lifecycleController.startServices()
        }

        override fun stopServices() {
            lifecycleController.stopServices()
            transferOrchestrator.resetClient()
        }

        override fun startDiscovery() {
            lifecycleController.startDiscovery()
        }

        override fun stopDiscovery() {
            lifecycleController.stopDiscovery()
        }

        override suspend fun sendMemo(
            device: DiscoveredDevice,
            content: String,
            timestamp: Long,
            attachmentUris: Map<String, String>,
        ): Result<Unit> =
            transferOrchestrator.sendMemo(
                device = device,
                content = content,
                timestamp = timestamp,
                attachmentUris = attachmentUris,
            )

        override fun acceptIncoming() {
            lifecycleController.acceptIncoming()
            _incomingShare.value = IncomingShareState.None
        }

        override fun rejectIncoming() {
            lifecycleController.rejectIncoming()
            _incomingShare.value = IncomingShareState.None
        }

        override fun resetTransferState() {
            transferOrchestrator.resetTransferState()
        }

        override suspend fun setLanShareE2eEnabled(enabled: Boolean) {
            pairingConfig.setLanShareE2eEnabled(enabled)
        }

        override suspend fun setLanSharePairingCode(pairingCode: String) {
            pairingConfig.setLanSharePairingCode(pairingCode)
        }

        override suspend fun clearLanSharePairingCode() {
            pairingConfig.clearLanSharePairingCode()
        }

        override suspend fun setLanShareDeviceName(deviceName: String) {
            pairingConfig.setLanShareDeviceName(deviceName)
            lifecycleController.refreshServiceRegistration()
        }

        override suspend fun requiresPairingBeforeSend(): Boolean = pairingConfig.requiresPairingBeforeSend()
    }
