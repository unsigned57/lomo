package com.lomo.app.feature.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.navigation.ShareRoutePayloadStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
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
                    // Extract attachment URIs from memo content
                    val attachmentUris = extractAttachmentUris(currentContent)
                    val result =
                        shareServiceManager.sendMemo(
                            device = device,
                            content = currentContent,
                            timestamp = memoTimestamp,
                            attachmentUris = attachmentUris,
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
                    _pairingCodeError.value = INVALID_PAIRING_CODE_ERROR_MESSAGE
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

        private fun extractAttachmentUris(content: String): Map<String, String> {
            return extractLocalAttachmentPaths(content).associateWith { it }
        }

        private fun extractLocalAttachmentPaths(content: String): List<String> {
            val markdownImages = MARKDOWN_IMAGE_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }
            val wikiImages =
                WIKI_IMAGE_PATTERN.findAll(content).mapNotNull { match ->
                    match.groupValues.getOrNull(1)?.substringBefore('|')
                }
            val audioLinks = AUDIO_LINK_PATTERN.findAll(content).mapNotNull { it.groupValues.getOrNull(1) }

            return (markdownImages + wikiImages + audioLinks)
                .map { it.trim() }
                .filter { path ->
                    path.isNotEmpty() &&
                        !path.startsWith("http://", ignoreCase = true) &&
                        !path.startsWith("https://", ignoreCase = true)
                }.distinct()
                .toList()
        }

        private companion object {
            private val MARKDOWN_IMAGE_PATTERN = Regex("!\\[.*?\\]\\((.*?)\\)")
            private val WIKI_IMAGE_PATTERN = Regex("!\\[\\[(.*?)\\]\\]")
            private val AUDIO_LINK_PATTERN =
                Regex(
                    "(?<!!)\\[[^\\]]*\\]\\((.+?\\.(?:m4a|mp3|ogg|wav|aac))\\)",
                    RegexOption.IGNORE_CASE,
                )
            private const val INVALID_PAIRING_CODE_ERROR_MESSAGE = "Pairing code must be 6-64 characters"
        }

        private fun reportOperationError(
            throwable: Throwable,
            fallbackMessage: String,
        ) {
            if (throwable is CancellationException) throw throwable
            val message =
                sanitizeUserFacingMessage(
                    rawMessage = throwable.message,
                    fallbackMessage = fallbackMessage,
                )
            _operationError.value = message
            Timber.e(throwable, "ShareViewModel operation failed: %s", message)
        }

        private fun sanitizeUserFacingMessage(
            rawMessage: String?,
            fallbackMessage: String,
        ): String {
            val message = rawMessage?.trim().orEmpty()
            if (message.isBlank()) return fallbackMessage
            if (looksTechnicalErrorMessage(message)) return fallbackMessage
            return message
        }

        private fun looksTechnicalErrorMessage(message: String): Boolean =
            message.length > 200 ||
                message.contains('\n') ||
                message.contains('\r') ||
                message.contains("exception", ignoreCase = true) ||
                message.contains("java.", ignoreCase = true) ||
                message.contains("kotlin.", ignoreCase = true) ||
                message.contains("stacktrace", ignoreCase = true) ||
                message.contains("\tat")
    }
