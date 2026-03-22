package com.lomo.app.feature.share

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.repository.LanShareService
import javax.inject.Inject

class LanShareUiCoordinator
    @Inject
    constructor(
        private val lanShareService: LanShareService,
    ) {
        val discoveredDevices = lanShareService.discoveredDevices
        val transferState = lanShareService.transferState
        val lanShareE2eEnabled = lanShareService.lanShareE2eEnabled
        val lanSharePairingConfigured = lanShareService.lanSharePairingConfigured
        val lanSharePairingCode = lanShareService.lanSharePairingCode
        val lanShareDeviceName = lanShareService.lanShareDeviceName

        fun startDiscovery() {
            lanShareService.startDiscovery()
        }

        fun stopDiscovery() {
            lanShareService.stopDiscovery()
        }

        suspend fun requiresPairingBeforeSend(): Boolean = lanShareService.requiresPairingBeforeSend()

        suspend fun sendMemo(
            device: DiscoveredDevice,
            content: String,
            timestamp: Long,
            attachmentUris: Map<String, String>,
        ): Result<Unit> =
            lanShareService.sendMemo(
                device = device,
                content = content,
                timestamp = timestamp,
                attachmentUris = attachmentUris,
            )

        suspend fun setLanShareE2eEnabled(enabled: Boolean) {
            lanShareService.setLanShareE2eEnabled(enabled)
        }

        suspend fun setLanSharePairingCode(pairingCode: String) {
            lanShareService.setLanSharePairingCode(pairingCode)
        }

        suspend fun clearLanSharePairingCode() {
            lanShareService.clearLanSharePairingCode()
        }

        suspend fun setLanShareDeviceName(deviceName: String) {
            lanShareService.setLanShareDeviceName(deviceName)
        }

        fun resetTransferState() {
            lanShareService.resetTransferState()
        }
    }
