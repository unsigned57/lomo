package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.LanShareStartupFailure
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

private const val PAIRING_REQUIRED_EVENT_INCREMENT = 1
private const val LAN_SHARE_DISABLED_MESSAGE = "LAN share is disabled in settings."

@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        private val lanShareUiCoordinator: LanShareUiCoordinator,
        private val extractShareAttachmentsUseCase: ExtractShareAttachmentsUseCase,
        private val shareErrorPolicy: ShareErrorPolicy,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val memoPayloadKey: String = savedStateHandle.get<String>("payloadKey").orEmpty()
        private val legacyMemoContent: String = savedStateHandle.get<String>("memoContent").orEmpty()
        private var memoContentBacking: String =
            ShareRoutePayloadStore.consumeMemoContent(memoPayloadKey).orEmpty().ifBlank {
                legacyMemoContent
            }

        val memoContent: String
            get() = memoContentBacking

        val memoTimestamp: Long = savedStateHandle.get<Long>("memoTimestamp") ?: 0L

        val discoveredDevices =
            lanShareUiCoordinator.discoveredDevices
                .stateIn(viewModelScope, appWhileSubscribed(), emptyList())

        val transferState =
            lanShareUiCoordinator.transferState
                .stateIn(viewModelScope, appWhileSubscribed(), ShareTransferState.Idle)

        val lanShareEnabled =
            lanShareUiCoordinator.lanShareEnabled
                .stateIn(viewModelScope, appWhileSubscribed(), true)

        val lanShareE2eEnabled =
            lanShareUiCoordinator.lanShareE2eEnabled
                .stateIn(viewModelScope, appWhileSubscribed(), true)

        val lanSharePairingConfigured =
            lanShareUiCoordinator.lanSharePairingConfigured
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val lanSharePairingCode =
            lanShareUiCoordinator.lanSharePairingCode
                .stateIn(viewModelScope, appWhileSubscribed(), "")

        val lanShareDeviceName =
            lanShareUiCoordinator.lanShareDeviceName
                .stateIn(viewModelScope, appWhileSubscribed(), "")

        private val _pairingCodeError = MutableStateFlow<String?>(null)
        val pairingCodeError: StateFlow<String?> = _pairingCodeError.asStateFlow()
        private val _pairingRequiredEvent = MutableStateFlow(0)
        val pairingRequiredEvent: StateFlow<Int> = _pairingRequiredEvent.asStateFlow()
        private val _operationError = MutableStateFlow<String?>(null)
        val operationError: StateFlow<String?> = _operationError.asStateFlow()
        private val _lanSharePermissionState =
            MutableStateFlow<LanSharePermissionState>(LanSharePermissionState.Unrequested)
        val lanSharePermissionState: StateFlow<LanSharePermissionState> = _lanSharePermissionState.asStateFlow()
        private val _lanShareDiscoveryError = MutableStateFlow<String?>(null)
        val lanShareDiscoveryError: StateFlow<String?> = _lanShareDiscoveryError.asStateFlow()
        val isTechnicalShareError: (String) -> Boolean = shareErrorPolicy::isTechnicalMessage

        init {
            if (memoContentBacking.isBlank()) {
                _operationError.value = "Share content is unavailable. Please reopen the share page."
            }
            viewModelScope.launch {
                lanShareUiCoordinator.lanShareStartupFailures.collect { failure ->
                    val message = failure.userFacingMessage()
                    _lanShareDiscoveryError.value = message
                    _operationError.value = message
                }
            }
        }

        fun startLanShareDiscoverySession() {
            clearLanShareDiscoveryError()
            viewModelScope.launch {
                if (!lanShareUiCoordinator.isLanShareEnabled()) {
                    return@launch
                }
                runCatching {
                    lanShareUiCoordinator.startServices()
                    lanShareUiCoordinator.startDiscovery()
                    Timber.d("ShareViewModel: discovery session started")
                }.onFailure { throwable ->
                    reportOperationError(throwable, "Failed to start device discovery")
                }
            }
        }

        val onLanShareNetworkPermissionsGranted: () -> Unit = {
            _lanSharePermissionState.value = LanSharePermissionState.Granted
            clearLanShareDiscoveryError()
            startLanShareDiscoverySession()
        }

        val onLanShareNetworkPermissionsDenied: () -> Unit = {
            _lanSharePermissionState.value = LanSharePermissionState.Denied
            _lanShareDiscoveryError.value = null
        }

        fun sendMemo(device: DiscoveredDevice) {
            viewModelScope.launch {
                runCatching {
                    val currentContent = memoContentBacking
                    if (currentContent.isBlank()) {
                        _operationError.value = "Share content is unavailable. Please reopen the share page."
                        return@launch
                    }

                    if (!lanShareUiCoordinator.isLanShareEnabled()) {
                        _operationError.value = LAN_SHARE_DISABLED_MESSAGE
                        return@launch
                    }

                    if (lanShareUiCoordinator.requiresPairingBeforeSend()) {
                        _pairingRequiredEvent.value += PAIRING_REQUIRED_EVENT_INCREMENT
                        return@launch
                    }
                    val attachmentResult = extractShareAttachmentsUseCase(currentContent)
                    val result =
                        lanShareUiCoordinator.sendMemo(
                            device = device,
                            content = currentContent,
                            timestamp = memoTimestamp,
                            attachmentUris = attachmentResult.attachmentUris,
                        )
                    result.exceptionOrNull()?.let { throwable ->
                        reportOperationError(throwable, "Failed to send memo")
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    reportOperationError(throwable, "Failed to send memo")
                }
            }
        }

        fun updateLanShareE2eEnabled(enabled: Boolean) {
            viewModelScope.launch {
                runCatching {
                    lanShareUiCoordinator.setLanShareE2eEnabled(enabled)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    reportOperationError(throwable, "Failed to update secure share setting")
                }
            }
        }

        fun updateLanSharePairingCode(pairingCode: String) {
            viewModelScope.launch {
                runCatching {
                    lanShareUiCoordinator.setLanSharePairingCode(pairingCode)
                    _pairingCodeError.value = null
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    _pairingCodeError.value = LanSharePairingCodePolicy.saveFailureMessage(throwable)
                }
            }
        }

        fun clearLanSharePairingCode() {
            viewModelScope.launch {
                runCatching {
                    lanShareUiCoordinator.clearLanSharePairingCode()
                    _pairingCodeError.value = null
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    reportOperationError(throwable, "Failed to clear pairing code")
                }
            }
        }

        val clearPairingCodeError: () -> Unit = {
            _pairingCodeError.value = null
        }

        val clearOperationError: () -> Unit = {
            _operationError.value = null
            _lanShareDiscoveryError.value = null
        }

        fun updateLanShareDeviceName(deviceName: String) {
            viewModelScope.launch {
                runCatching {
                    lanShareUiCoordinator.setLanShareDeviceName(deviceName)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    reportOperationError(throwable, "Failed to update device name")
                }
            }
        }

        fun resetTransferState() {
            runCatching {
                lanShareUiCoordinator.resetTransferState()
            }.onFailure { throwable ->
                reportOperationError(throwable, "Failed to reset transfer state")
            }
        }

        override fun onCleared() {
            super.onCleared()
            runCatching {
                lanShareUiCoordinator.stopDiscovery()
                Timber.d("ShareViewModel cleared: discovery stopped")
            }.onFailure { throwable ->
                reportOperationError(throwable, "Failed to stop device discovery")
            }
        }

        private fun reportOperationError(
            throwable: Throwable,
            fallbackMessage: String,
        ) {
            if (throwable is CancellationException) throw throwable
            val message = throwable.toUserMessage(fallbackMessage, shareErrorPolicy::sanitizeUserFacingMessage)
            _operationError.value = message
            Timber.e(throwable, "ShareViewModel operation failed: %s", message)
        }

        private fun LanShareStartupFailure.userFacingMessage(): String =
            when (this) {
                LanShareStartupFailure.DiscoveryStartFailed -> "Failed to start device discovery"
                LanShareStartupFailure.ServiceRegistrationFailed ->
                    "Failed to register this device for LAN sharing"
            }

        private fun clearLanShareDiscoveryError() {
            val previousDiscoveryError = _lanShareDiscoveryError.value
            _lanShareDiscoveryError.value = null
            if (_operationError.value == previousDiscoveryError) {
                _operationError.value = null
            }
        }
    }
