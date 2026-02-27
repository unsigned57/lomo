package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import dagger.hilt.android.lifecycle.HiltViewModel
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
        private val textProcessor: com.lomo.data.util.MemoTextProcessor,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val memoContent: String = savedStateHandle.get<String>("memoContent") ?: ""
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

        init {
            // Discovery starts when entering the share screen; server lifecycle is managed by MainActivity.
            shareServiceManager.startDiscovery()
            Timber.d("ShareViewModel init: discovery started")
        }

        fun sendMemo(device: DiscoveredDevice) {
            viewModelScope.launch {
                if (shareServiceManager.requiresPairingBeforeSend()) {
                    _pairingRequiredEvent.value += 1
                    return@launch
                }
                // Extract attachment URIs from memo content
                val attachmentUris = extractAttachmentUris(memoContent)

                shareServiceManager.sendMemo(
                    device = device,
                    content = memoContent,
                    timestamp = memoTimestamp,
                    attachmentUris = attachmentUris,
                )
            }
        }

        fun updateLanShareE2eEnabled(enabled: Boolean) {
            viewModelScope.launch {
                shareServiceManager.setLanShareE2eEnabled(enabled)
            }
        }

        fun updateLanSharePairingCode(pairingCode: String) {
            viewModelScope.launch {
                try {
                    shareServiceManager.setLanSharePairingCode(pairingCode)
                    _pairingCodeError.value = null
                } catch (e: Exception) {
                    _pairingCodeError.value = e.message ?: "Pairing code must be 6-64 characters"
                }
            }
        }

        fun clearLanSharePairingCode() {
            viewModelScope.launch {
                shareServiceManager.clearLanSharePairingCode()
                _pairingCodeError.value = null
            }
        }

        fun clearPairingCodeError() {
            _pairingCodeError.value = null
        }

        fun updateLanShareDeviceName(deviceName: String) {
            viewModelScope.launch {
                shareServiceManager.setLanShareDeviceName(deviceName)
            }
        }

        fun resetTransferState() {
            shareServiceManager.resetTransferState()
        }

        override fun onCleared() {
            super.onCleared()
            shareServiceManager.stopDiscovery()
            Timber.d("ShareViewModel cleared: discovery stopped")
        }

        private fun extractAttachmentUris(content: String): Map<String, String> {
            val attachments = mutableMapOf<String, String>()
            textProcessor.extractLocalAttachmentPaths(content).forEach { path ->
                attachments[path] = path
            }

            return attachments
        }
    }
