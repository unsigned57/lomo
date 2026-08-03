package com.lomo.app.feature.share

import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.repository.LanShareService
import kotlinx.coroutines.flow.first


class LanShareUiCoordinator(
    private val lanShareService: LanShareService,
) {
        val discoveredDevices = lanShareService.discoveredDevices
        val pendingPairing = lanShareService.pendingPairing
        val incomingBatch = lanShareService.incomingBatch
        val trustedPeers = lanShareService.trustedPeers
        val transferState = lanShareService.transferState
        val lanShareRuntimeState = lanShareService.lanShareRuntimeState
        val lanShareDiscoveryDiagnostics = lanShareService.lanShareDiscoveryDiagnostics
        val lanShareStartupFailures = lanShareService.lanShareStartupFailures
        val lanShareEnabled = lanShareService.lanShareEnabled
        val lanShareDeviceName = lanShareService.lanShareDeviceName
        val refreshNetworkPermissionState: () -> Unit = lanShareService::refreshNetworkPermissionState

        val startDiscovery: () -> Unit = lanShareService::startDiscovery

        fun startServices() {
            lanShareService.startServices()
        }

        fun stopDiscovery() {
            lanShareService.stopDiscovery()
        }

        suspend fun isLanShareEnabled(): Boolean = lanShareService.lanShareEnabled.first()

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

        fun confirmPairing(pairingId: String) {
            lanShareService.confirmPairing(pairingId)
        }

        fun declinePairing(pairingId: String) {
            lanShareService.declinePairing(pairingId)
        }

        fun approveIncoming(sessionId: String, batchId: String) {
            lanShareService.approveIncoming(sessionId, batchId)
        }

        fun rejectIncoming(sessionId: String, batchId: String) {
            lanShareService.rejectIncoming(sessionId, batchId)
        }

        fun revokePeer(deviceId: String) {
            lanShareService.revokePeer(deviceId)
        }

        suspend fun setLanShareDeviceName(deviceName: String) {
            lanShareService.setLanShareDeviceName(deviceName)
        }

        fun resetTransferState() {
            lanShareService.resetTransferState()
        }
    }
