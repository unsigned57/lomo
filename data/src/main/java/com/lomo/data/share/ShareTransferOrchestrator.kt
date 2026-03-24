package com.lomo.data.share

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferErrorPolicy
import com.lomo.domain.model.ShareTransferState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class ShareTransferOrchestrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
        private val pairingConfig: SharePairingConfig,
    ) {
        private val _transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
        val transferState: StateFlow<ShareTransferState> = _transferState.asStateFlow()

        private var client = createShareClient()
        private val attachmentResolver = ShareAttachmentResolver(context, dataStore)

        suspend fun sendMemo(
            device: DiscoveredDevice,
            content: String,
            timestamp: Long,
            attachmentUris: Map<String, String>,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runNonFatalCatching {
                    val e2eEnabled = pairingConfig.isE2eEnabled()
                    if (!e2eEnabled) {
                        Timber.tag(TAG).w("LAN share is in OPEN mode: transfer is unauthenticated")
                    }
                    performSend(device, content, timestamp, attachmentUris, e2eEnabled)
                }.getOrElse { error ->
                    Timber.tag(TAG).e(error, "Send memo failed")
                    _transferState.value = ShareTransferState.Error(ShareTransferErrorPolicy.unknown(error.message))
                    Result.failure(error)
                }
            }

        private suspend fun performSend(
            device: DiscoveredDevice,
            content: String,
            timestamp: Long,
            attachmentUris: Map<String, String>,
            e2eEnabled: Boolean,
        ): Result<Unit> =
            pairingConfig
                .requiresPairingBeforeSend()
                .takeIf { it }
                ?.let { failWithError(ShareTransferErrorPolicy.pairingRequiredBeforeSend()) }
                ?: run {
                    _transferState.value = ShareTransferState.Sending
                    when (val preparedAttachments = attachmentResolver.prepareAttachments(attachmentUris)) {
                        is ShareAttachmentResolver.AttachmentPreparationResult.Failure -> {
                            failWithError(preparedAttachments.error)
                        }

                        is ShareAttachmentResolver.AttachmentPreparationResult.Success -> {
                            val attachments = preparedAttachments.prepared
                            _transferState.value = ShareTransferState.WaitingApproval(device.name)
                            val prepareResult =
                                client.prepare(
                                    device = device,
                                    content = content,
                                    timestamp = timestamp,
                                    senderName = pairingConfig.resolveDeviceName(),
                                    attachments = attachments.infos,
                                    e2eEnabled = e2eEnabled,
                                )
                            when {
                                prepareResult.isFailure -> {
                                    val throwable = prepareResult.exceptionOrNull()
                                    val transferError =
                                        ShareTransferErrorPolicy.connectionFailed(throwable?.message)
                                    Timber.tag(TAG).e(throwable, "Prepare failed: $transferError")
                                    _transferState.value = ShareTransferState.Error(transferError)
                                    Result.failure(throwable ?: toException(transferError))
                                }

                                prepareResult.getOrNull()?.sessionToken.isNullOrBlank() -> {
                                    failWithError(ShareTransferErrorPolicy.transferRejected(device.name))
                                }

                                else -> {
                                    val prepared = requireNotNull(prepareResult.getOrNull())
                                    _transferState.value = ShareTransferState.Transferring(0f)
                                    val success =
                                        client.transfer(
                                            device = device,
                                            content = content,
                                            timestamp = timestamp,
                                            sessionToken = prepared.sessionToken.orEmpty(),
                                            attachmentUris = attachments.uris,
                                            e2eEnabled = e2eEnabled,
                                            e2eKeyHex = prepared.keyHex,
                                        )
                                    if (success) {
                                        _transferState.value = ShareTransferState.Success(device.name)
                                        Result.success(Unit)
                                    } else {
                                        failWithError(ShareTransferErrorPolicy.transferFailed())
                                    }
                                }
                            }
                        }
                    }
                }

        fun resetTransferState() {
            _transferState.value = ShareTransferState.Idle
        }

        fun resetClient() {
            client.close()
            client = createShareClient()
        }

        private fun failWithError(error: ShareTransferError): Result<Unit> {
            _transferState.value = ShareTransferState.Error(error)
            return Result.failure(toException(error))
        }

        private fun toException(error: ShareTransferError): Exception {
            val detail = error.detail?.takeIf { it.isNotBlank() }
            val message =
                if (detail == null) {
                    "Share transfer failed [${error.code}]"
                } else {
                    "Share transfer failed [${error.code}]: $detail"
                }
            return IllegalStateException(message)
        }

        private fun createShareClient(): LomoShareClient =
            LomoShareClient(
                context = context,
                getPairingKeyHex = { pairingConfig.getEffectivePairingKeyHex() },
            )

        private companion object {
            private const val TAG = "ShareTransferOrchestrator"
        }
    }
