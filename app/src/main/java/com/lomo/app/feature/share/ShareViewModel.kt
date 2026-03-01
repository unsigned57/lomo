package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.lanshare.LanSharePairingCodePolicy
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.usecase.ExtractShareAttachmentsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        private val shareServiceManager: LanShareService,
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
            shareServiceManager.discoveredDevices
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        val transferState =
            shareServiceManager.transferState
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShareTransferState.Idle)

        val lanShareE2eEnabled =
            shareServiceManager.lanShareE2eEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

        val lanSharePairingConfigured =
            shareServiceManager.lanSharePairingConfigured
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        val lanSharePairingCode =
            shareServiceManager.lanSharePairingCode
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        val lanShareDeviceName =
            shareServiceManager.lanShareDeviceName
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

        private val _pairingCodeError = MutableStateFlow<String?>(null)
        val pairingCodeError: StateFlow<String?> = _pairingCodeError.asStateFlow()
        private val _pairingRequiredEvent = MutableStateFlow(0)
        val pairingRequiredEvent: StateFlow<Int> = _pairingRequiredEvent.asStateFlow()
        private val _operationError = MutableStateFlow<String?>(null)
        val operationError: StateFlow<String?> = _operationError.asStateFlow()

        init {
            // Discovery starts when entering the share screen; server lifecycle is managed by MainActivity.
            runCatching {
                shareServiceManager.startDiscovery()
                Timber.d("ShareViewModel init: discovery started")
            }.onFailure { throwable ->
                reportOperationError(throwable, "Failed to start device discovery")
            }

            if (memoContentBacking.isBlank()) {
                _operationError.value = "Share content is unavailable. Please reopen the share page."
            }
        }

        fun sendMemo(device: DiscoveredDevice) {
            viewModelScope.launch {
                try {
                    val currentContent = memoContentBacking
                    if (currentContent.isBlank()) {
                        _operationError.value = "Share content is unavailable. Please reopen the share page."
                        return@launch
                    }

                    if (shareServiceManager.requiresPairingBeforeSend()) {
                        _pairingRequiredEvent.value += 1
                        return@launch
                    }
                    val attachmentResult = extractShareAttachmentsUseCase(currentContent)
                    val result =
                        shareServiceManager.sendMemo(
                            device = device,
                            content = currentContent,
                            timestamp = memoTimestamp,
                            attachmentUris = attachmentResult.attachmentUris,
                        )
                    result.exceptionOrNull()?.let { throwable ->
                        reportOperationError(throwable, "Failed to send memo")
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to send memo")
                }
            }
        }

        fun updateLanShareE2eEnabled(enabled: Boolean) {
            viewModelScope.launch {
                try {
                    shareServiceManager.setLanShareE2eEnabled(enabled)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to update secure share setting")
                }
            }
        }

        fun updateLanSharePairingCode(pairingCode: String) {
            viewModelScope.launch {
                try {
                    shareServiceManager.setLanSharePairingCode(pairingCode)
                    _pairingCodeError.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    _pairingCodeError.value = LanSharePairingCodePolicy.saveFailureMessage(e)
                }
            }
        }

        fun clearLanSharePairingCode() {
            viewModelScope.launch {
                try {
                    shareServiceManager.clearLanSharePairingCode()
                    _pairingCodeError.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to clear pairing code")
                }
            }
        }

        fun clearPairingCodeError() {
            _pairingCodeError.value = null
        }

        fun clearOperationError() {
            _operationError.value = null
        }

        fun isTechnicalShareError(message: String): Boolean = shareErrorPolicy.isTechnicalMessage(message)

        fun updateLanShareDeviceName(deviceName: String) {
            viewModelScope.launch {
                try {
                    shareServiceManager.setLanShareDeviceName(deviceName)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    reportOperationError(throwable, "Failed to update device name")
                }
            }
        }

        fun resetTransferState() {
            runCatching {
                shareServiceManager.resetTransferState()
            }.onFailure { throwable ->
                reportOperationError(throwable, "Failed to reset transfer state")
            }
        }

        override fun onCleared() {
            super.onCleared()
            runCatching {
                shareServiceManager.stopDiscovery()
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
    }
