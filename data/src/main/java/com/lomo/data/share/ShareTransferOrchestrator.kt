package com.lomo.data.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.ShareTransferError
import com.lomo.domain.model.ShareTransferState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class ShareTransferOrchestrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
        private val pairingConfig: SharePairingConfig,
    ) {
        private companion object {
            private const val TAG = "ShareTransferOrchestrator"
        }

        private val _transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
        val transferState: StateFlow<ShareTransferState> = _transferState.asStateFlow()

        private var client = createShareClient()

        suspend fun sendMemo(
            device: DiscoveredDevice,
            content: String,
            timestamp: Long,
            attachmentUris: Map<String, String>,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val e2eEnabled = pairingConfig.isE2eEnabled()
                    if (!e2eEnabled) {
                        Timber.tag(TAG).w("LAN share is in OPEN mode: transfer is unauthenticated")
                    }
                    if (pairingConfig.requiresPairingBeforeSend()) {
                        val error = ShareTransferErrorPolicy.pairingRequiredBeforeSend()
                        return@withContext failWithError(error)
                    }

                    _transferState.value = ShareTransferState.Sending

                    val parsedUris = attachmentUris.mapValues { (_, value) -> Uri.parse(value) }
                    val resolvedAttachmentUris = resolveAttachmentUris(parsedUris)
                    if (resolvedAttachmentUris.size != attachmentUris.size) {
                        val missingCount = attachmentUris.size - resolvedAttachmentUris.size
                        val error = ShareTransferErrorPolicy.missingAttachments(missingCount)
                        return@withContext failWithError(error)
                    }
                    if (!ShareTransferLimits.isAttachmentCountValid(resolvedAttachmentUris.size)) {
                        val error = ShareTransferErrorPolicy.tooManyAttachments()
                        return@withContext failWithError(error)
                    }

                    val attachmentInfos = mutableListOf<ShareAttachmentInfo>()
                    var totalAttachmentBytes = 0L
                    for ((name, uri) in resolvedAttachmentUris) {
                        val resolvedSize = resolveAttachmentSize(uri)
                        if (resolvedSize > ShareTransferLimits.maxAttachmentPayloadBytes(e2eEnabled = false)) {
                            val error = ShareTransferErrorPolicy.attachmentTooLarge()
                            return@withContext failWithError(error)
                        }
                        val size = sanitizeAttachmentSizeForPrepare(resolvedSize)
                        totalAttachmentBytes += size
                        if (totalAttachmentBytes > ShareTransferLimits.maxTotalAttachmentPayloadBytes(e2eEnabled = false)) {
                            val error = ShareTransferErrorPolicy.attachmentsTooLarge()
                            return@withContext failWithError(error)
                        }
                        val type = detectAttachmentType(name, uri)
                        attachmentInfos += ShareAttachmentInfo(name = name, type = type, size = size)
                    }
                    if (attachmentInfos.any { it.type == "unknown" }) {
                        val error = ShareTransferErrorPolicy.unsupportedAttachmentType()
                        return@withContext failWithError(error)
                    }

                    _transferState.value = ShareTransferState.WaitingApproval(device.name)
                    val prepareResult =
                        client.prepare(
                            device = device,
                            content = content,
                            timestamp = timestamp,
                            senderName = pairingConfig.resolveDeviceName(),
                            attachments = attachmentInfos,
                            e2eEnabled = e2eEnabled,
                        )

                    if (prepareResult.isFailure) {
                        val throwable = prepareResult.exceptionOrNull()
                        val transferError = ShareTransferErrorPolicy.connectionFailed(throwable?.message)
                        Timber.tag(TAG).e(throwable, "Prepare failed: $transferError")
                        _transferState.value = ShareTransferState.Error(transferError)
                        return@withContext Result.failure(throwable ?: toException(transferError))
                    }

                    val prepared = prepareResult.getOrNull()
                    val sessionToken = prepared?.sessionToken
                    if (sessionToken.isNullOrBlank()) {
                        val error = ShareTransferErrorPolicy.transferRejected(device.name)
                        return@withContext failWithError(error)
                    }

                    _transferState.value = ShareTransferState.Transferring(0f)
                    val success =
                        client.transfer(
                            device = device,
                            content = content,
                            timestamp = timestamp,
                            sessionToken = sessionToken,
                            attachmentUris = resolvedAttachmentUris,
                            e2eEnabled = e2eEnabled,
                            e2eKeyHex = prepared.keyHex,
                        )

                    if (success) {
                        _transferState.value = ShareTransferState.Success(device.name)
                        Result.success(Unit)
                    } else {
                        val error = ShareTransferErrorPolicy.transferFailed()
                        failWithError(error)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Send memo failed")
                    _transferState.value = ShareTransferState.Error(ShareTransferErrorPolicy.unknown(e.message))
                    Result.failure(e)
                }
            }

        fun resetTransferState() {
            _transferState.value = ShareTransferState.Idle
        }

        fun resetClient() {
            client.close()
            client = createShareClient()
        }

        private suspend fun resolveAttachmentUris(attachmentUris: Map<String, Uri>): Map<String, Uri> {
            if (attachmentUris.isEmpty()) return emptyMap()
            val resolved = linkedMapOf<String, Uri>()
            for ((reference, uri) in attachmentUris) {
                val resolvedUri = resolveAttachmentUri(reference, uri)
                if (resolvedUri != null) {
                    resolved[reference] = resolvedUri
                } else {
                    Timber.tag(TAG).w("Attachment not found for share reference: $reference")
                }
            }
            return resolved
        }

        private suspend fun resolveAttachmentUri(
            reference: String,
            uri: Uri,
        ): Uri? {
            if (uri.scheme == "content" || uri.scheme == "file") {
                return uri
            }

            val normalizedReference = reference.trim()
            if (normalizedReference.isBlank()) return null

            val absoluteCandidate = File(normalizedReference)
            if (absoluteCandidate.isAbsolute && absoluteCandidate.exists()) {
                return Uri.fromFile(absoluteCandidate)
            }

            val fileName = normalizedReference.substringAfterLast('/').substringAfterLast('\\')
            if (fileName.isBlank()) return null

            resolveFromDirectory(dataStore.imageDirectory.first(), fileName)?.let { return it }
            resolveFromDirectory(dataStore.voiceDirectory.first(), fileName)?.let { return it }
            resolveFromDirectory(dataStore.rootDirectory.first(), fileName)?.let { return it }

            resolveFromTreeUri(dataStore.imageUri.first(), fileName)?.let { return it }
            resolveFromTreeUri(dataStore.voiceUri.first(), fileName)?.let { return it }
            resolveFromTreeUri(dataStore.rootUri.first(), fileName)?.let { return it }

            return null
        }

        private fun resolveFromDirectory(
            directory: String?,
            fileName: String,
        ): Uri? {
            if (directory.isNullOrBlank()) return null
            val file = File(directory, fileName)
            return if (file.exists() && file.isFile) {
                Uri.fromFile(file)
            } else {
                null
            }
        }

        private fun resolveFromTreeUri(
            treeUriString: String?,
            fileName: String,
        ): Uri? {
            if (treeUriString.isNullOrBlank()) return null
            return try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return null
                tree.findFile(fileName)?.uri
            } catch (_: Exception) {
                null
            }
        }

        private fun resolveAttachmentSize(uri: Uri): Long {
            queryAttachmentSize(uri)?.let { return it }

            if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        return file.length().coerceAtLeast(0L)
                    }
                }
            }

            return try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L } ?: 0L
                } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }

        private fun queryAttachmentSize(uri: Uri): Long? {
            return try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return null
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index < 0 || cursor.isNull(index)) return null
                    cursor.getLong(index).takeIf { it >= 0L }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun sanitizeAttachmentSizeForPrepare(size: Long): Long = size.coerceAtLeast(0L)

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

        private fun detectAttachmentType(
            name: String,
            uri: Uri,
        ): String {
            val byName =
                when {
                    name.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)) -> "image"
                    name.matches(Regex(".*\\.(m4a|mp3|ogg|wav)$", RegexOption.IGNORE_CASE)) -> "audio"
                    else -> "unknown"
                }
            if (byName != "unknown") return byName

            val mime =
                try {
                    context.contentResolver.getType(uri)?.lowercase()
                } catch (_: Exception) {
                    null
                }
            return when {
                mime?.startsWith("image/") == true -> "image"
                mime?.startsWith("audio/") == true -> "audio"
                else -> "unknown"
            }
        }

        private fun createShareClient(): LomoShareClient =
            LomoShareClient(
                context = context,
                getPairingKeyHex = { pairingConfig.getEffectivePairingKeyHex() },
            )
    }
