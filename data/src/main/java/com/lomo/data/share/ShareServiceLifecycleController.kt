package com.lomo.data.share

import android.content.Context
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.SharePayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ShareServiceLifecycleController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val pairingConfig: SharePairingConfig,
    ) {
        private companion object {
            private const val TAG = "ShareServiceLifecycle"
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val nsdService = NsdDiscoveryService(context)
        private val server = LomoShareServer()
        private val serviceStateLock = Any()

        val discoveredDevices: StateFlow<List<DiscoveredDevice>> = nsdService.discoveredDevices

        private var serverPort: Int = 0
        private val localUuid = Uuid.random().toString()

        @Volatile
        private var servicesStarted = false

        @Volatile
        private var discoveryStarted = false

        private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

        fun bindServerCallbacks(
            onIncomingPrepare: (SharePayload) -> Unit,
            onSaveAttachment: suspend (name: String, type: String, payloadFile: File) -> String?,
            onSaveMemo: suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit,
        ) {
            server.onIncomingPrepare = onIncomingPrepare
            server.onSaveAttachment = onSaveAttachment
            server.onSaveMemo = onSaveMemo
            server.getPairingKeyHex = { pairingConfig.getEffectivePairingKeyHex() }
            server.isE2eEnabled = { pairingConfig.isE2eEnabled() }
        }

        fun startServices() {
            val shouldStart =
                synchronized(serviceStateLock) {
                    if (servicesStarted) {
                        false
                    } else {
                        servicesStarted = true
                        true
                    }
                }
            if (!shouldStart) return

            acquireMulticastLock()

            scope.launch {
                try {
                    serverPort = server.start()
                    val deviceName = pairingConfig.resolveDeviceName()
                    nsdService.registerService(serverPort, deviceName, localUuid)
                    Timber.tag(TAG).d("Services started: server=$serverPort, device=$deviceName")
                } catch (e: Exception) {
                    synchronized(serviceStateLock) {
                        servicesStarted = false
                    }
                    releaseMulticastLockSafely()
                    runCatching { nsdService.unregisterService() }
                        .onFailure { Timber.tag(TAG).e(it, "Failed to clean NSD registration after start failure") }
                    runCatching { server.stop() }
                        .onFailure { Timber.tag(TAG).e(it, "Failed to stop server after start failure") }
                    Timber.tag(TAG).e(e, "Failed to start services")
                }
            }
        }

        fun stopServices() {
            synchronized(serviceStateLock) {
                servicesStarted = false
                discoveryStarted = false
            }

            releaseMulticastLockSafely()
            nsdService.stopDiscovery()
            nsdService.unregisterService()
            server.stop()
            Timber.tag(TAG).d("Services stopped")
        }

        fun startDiscovery() {
            val shouldStart =
                synchronized(serviceStateLock) {
                    if (discoveryStarted) {
                        false
                    } else {
                        discoveryStarted = true
                        true
                    }
                }
            if (!shouldStart) return
            nsdService.startDiscovery(localUuid)
        }

        fun stopDiscovery() {
            synchronized(serviceStateLock) {
                discoveryStarted = false
            }
            nsdService.stopDiscovery()
        }

        fun acceptIncoming() {
            server.acceptIncoming()
        }

        fun rejectIncoming() {
            server.rejectIncoming()
        }

        fun refreshServiceRegistration() {
            val shouldRefresh =
                synchronized(serviceStateLock) {
                    servicesStarted && serverPort > 0
                }
            if (!shouldRefresh) return

            scope.launch {
                try {
                    val deviceName = pairingConfig.resolveDeviceName()
                    nsdService.unregisterService()
                    nsdService.registerService(serverPort, deviceName, localUuid)
                    Timber.tag(TAG).d("Refreshed NSD service name: $deviceName")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to refresh NSD registration")
                }
            }
        }

        private fun acquireMulticastLock() {
            try {
                if (multicastLock == null) {
                    multicastLock = wifiManager?.createMulticastLock("lomo_share_lock")
                    multicastLock?.setReferenceCounted(true)
                }
                multicastLock?.acquire()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to acquire multicast lock")
            }
        }

        private fun releaseMulticastLockSafely() {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to release multicast lock")
            }
        }
    }
