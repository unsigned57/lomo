package com.lomo.data.share

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.SharePayload
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareConfigurationController
import com.lomo.domain.repository.LanShareLifecycleController
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.LanShareStateRepository
import com.lomo.domain.repository.LanShareTransferController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShareServiceManager
    @Inject
    constructor(
        stateRepository: LanShareStateDelegate,
        lifecycleController: LanShareLifecycleControllerImpl,
        transferController: LanShareTransferControllerImpl,
        configurationController: LanShareConfigurationControllerImpl,
    ) : LanShareService,
        LanShareStateRepository by stateRepository,
        LanShareLifecycleController by lifecycleController,
        LanShareTransferController by transferController,
        LanShareConfigurationController by configurationController

@Singleton
class ShareIncomingStateHolder
    @Inject
    constructor() {
        private val _incomingShare = MutableStateFlow<IncomingShareState>(IncomingShareState.None)
        val incomingShare: StateFlow<IncomingShareState> = _incomingShare.asStateFlow()

        fun showPending(payload: SharePayload) {
            _incomingShare.value = IncomingShareState.Pending(payload)
        }

        fun clear() {
            _incomingShare.value = IncomingShareState.None
        }
    }

@Singleton
class LanShareStateDelegate
    @Inject
    constructor(
        lifecycleController: ShareServiceLifecycleController,
        transferOrchestrator: ShareTransferOrchestrator,
        pairingConfig: SharePairingConfig,
        incomingStateHolder: ShareIncomingStateHolder,
        attachmentStorage: ShareAttachmentStorage,
        incomingMemoSaver: ShareIncomingMemoSaver,
    ) : LanShareStateRepository {
        override val discoveredDevices: StateFlow<List<DiscoveredDevice>> =
            lifecycleController.discoveredDevices

        override val incomingShare: StateFlow<IncomingShareState> = incomingStateHolder.incomingShare

        override val transferState: StateFlow<ShareTransferState> = transferOrchestrator.transferState

        override val lanShareE2eEnabled: Flow<Boolean> = pairingConfig.lanShareE2eEnabled
        override val lanSharePairingConfigured: Flow<Boolean> = pairingConfig.lanSharePairingConfigured
        override val lanSharePairingCode: StateFlow<String> = pairingConfig.lanSharePairingCode
        override val lanShareDeviceName: Flow<String> = pairingConfig.lanShareDeviceName

        init {
            lifecycleController.bindServerCallbacks(
                onIncomingPrepare = incomingStateHolder::showPending,
                onSaveAttachment = { name, type, payloadFile ->
                    attachmentStorage.saveAttachmentFile(name, type, payloadFile)
                },
                onDeleteAttachment = { savedPath, type ->
                    attachmentStorage.deleteSavedAttachment(savedPath, type)
                },
                onSaveMemo = { content, timestamp, attachmentMappings ->
                    incomingMemoSaver.saveReceivedMemo(content, timestamp, attachmentMappings)
                },
            )
        }
    }

@Singleton
class LanShareLifecycleControllerImpl
    @Inject
    constructor(
        private val lifecycleController: ShareServiceLifecycleController,
        private val transferOrchestrator: ShareTransferOrchestrator,
    ) : LanShareLifecycleController {
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
    }

@Singleton
class LanShareTransferControllerImpl
    @Inject
    constructor(
        private val lifecycleController: ShareServiceLifecycleController,
        private val transferOrchestrator: ShareTransferOrchestrator,
        private val incomingStateHolder: ShareIncomingStateHolder,
    ) : LanShareTransferController {
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
            incomingStateHolder.clear()
        }

        override fun rejectIncoming() {
            lifecycleController.rejectIncoming()
            incomingStateHolder.clear()
        }

        override fun resetTransferState() {
            transferOrchestrator.resetTransferState()
        }
    }

@Singleton
class LanShareConfigurationControllerImpl
    @Inject
    constructor(
        private val pairingConfig: SharePairingConfig,
        private val lifecycleController: ShareServiceLifecycleController,
    ) : LanShareConfigurationController {
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
