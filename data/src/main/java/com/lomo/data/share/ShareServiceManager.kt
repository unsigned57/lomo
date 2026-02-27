package com.lomo.data.share

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.repository.MemoSynchronizer
import com.lomo.data.source.FileDataSource
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.IncomingShareState
import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.SharePayload
import com.lomo.domain.model.ShareTransferState
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unified manager for LAN share services: NSD discovery, HTTP server, and HTTP client.
 * Coordinates the full send/receive lifecycle.
 */
@Singleton
@OptIn(ExperimentalUuidApi::class)
class ShareServiceManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val synchronizer: MemoSynchronizer,
        private val dataSource: FileDataSource,
        private val dataStore: LomoDataStore,
        private val mediaRepository: MediaRepository,
    ) : LanShareService {
        companion object {
            private const val TAG = "ShareServiceManager"
            private const val MAX_DEVICE_NAME_CHARS = 32
            private const val DEFAULT_DEVICE_NAME = "Android Device"
            private const val MAX_ATTACHMENTS = 20
            private const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
            private const val MAX_TOTAL_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val nsdService = NsdDiscoveryService(context)
        private val server = LomoShareServer()
        private var client = createShareClient()

        override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = nsdService.discoveredDevices

        private val _incomingShare = MutableStateFlow<IncomingShareState>(IncomingShareState.None)
        override val incomingShare: StateFlow<IncomingShareState> = _incomingShare.asStateFlow()

        private val _transferState = MutableStateFlow<ShareTransferState>(ShareTransferState.Idle)
        override val transferState: StateFlow<ShareTransferState> = _transferState.asStateFlow()
        private val pairingCodeInputState = MutableStateFlow("")

        override val lanShareE2eEnabled = dataStore.lanShareE2eEnabled
        override val lanSharePairingConfigured = dataStore.lanSharePairingKeyHex.map { ShareAuthUtils.isValidKeyHex(it) }
        override val lanSharePairingCode: StateFlow<String> = pairingCodeInputState.asStateFlow()
        override val lanShareDeviceName = dataStore.lanShareDeviceName.map { sanitizeDeviceName(it) ?: getFallbackDeviceName() }

        private var serverPort: Int = 0
        private val localUuid = Uuid.random().toString()
        private val serviceStateLock = Any()

        @Volatile
        private var servicesStarted = false

        @Volatile
        private var discoveryStarted = false

        private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

        // --- Lifecycle ---

        override fun startServices() {
            val shouldStart =
                synchronized(serviceStateLock) {
                    if (servicesStarted) {
                        false
                    } else {
                        servicesStarted = true
                        true
                    }
                }
            if (!shouldStart) {
                return
            }

            // Acquire multicast lock for reliable discovery
            try {
                if (multicastLock == null) {
                    multicastLock = wifiManager?.createMulticastLock("lomo_share_lock")
                    multicastLock?.setReferenceCounted(true)
                }
                multicastLock?.acquire()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to acquire multicast lock")
            }

            // Start HTTP server
            server.onIncomingPrepare = { payload ->
                _incomingShare.value = IncomingShareState.Pending(payload)
            }

            server.onSaveAttachment = { name, type, bytes ->
                // suspend 回调，直接调用挂起函数，避免 runBlocking 阻塞
                saveAttachmentFile(name, type, bytes)
            }

            server.onSaveMemo = { content, timestamp, attachmentMappings ->
                // suspend 回调，直接使用挂起函数
                saveReceivedMemo(content, timestamp, attachmentMappings)
            }
            server.getPairingKeyHex = {
                getEffectivePairingKeyHex()
            }
            server.isE2eEnabled = {
                dataStore.lanShareE2eEnabled.first()
            }

            scope.launch {
                try {
                    serverPort = server.start()

                    // Register NSD service with device name and UUID for self-identification
                    val deviceName = resolveDeviceName()
                    nsdService.registerService(serverPort, deviceName, localUuid)

                    Timber.tag(TAG).d("Services started: server=$serverPort, device=$deviceName")
                } catch (e: Exception) {
                    synchronized(serviceStateLock) {
                        servicesStarted = false
                    }
                    Timber.tag(TAG).e(e, "Failed to start services")
                }
            }
        }

        override fun stopServices() {
            synchronized(serviceStateLock) {
                servicesStarted = false
                discoveryStarted = false
            }

            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to release multicast lock")
            }

            nsdService.stopDiscovery()
            nsdService.unregisterService()
            server.stop()
            client.close()
            client = createShareClient()
            Timber.tag(TAG).d("Services stopped")
        }

        override fun startDiscovery() {
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

        override fun stopDiscovery() {
            synchronized(serviceStateLock) {
                discoveryStarted = false
            }
            nsdService.stopDiscovery()
        }

        // --- Sending ---

        /**
         * Send a memo to a peer device.
         * @param device Target device.
         * @param content Memo text content.
         * @param timestamp Memo original timestamp.
         * @param attachmentUris Map of filename -> content URI for images/audio in the memo.
         */
        override suspend fun sendMemo(
            device: DiscoveredDevice,
            content: String,
            timestamp: Long,
            attachmentUris: Map<String, String>,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    val e2eEnabled = dataStore.lanShareE2eEnabled.first()
                    if (!e2eEnabled) {
                        Timber.tag(TAG).w("LAN share is in OPEN mode: transfer is unauthenticated")
                    }
                    if (requiresPairingBeforeSend()) {
                        val message = "Please set an end-to-end encryption password first"
                        _transferState.value = ShareTransferState.Error(message)
                        return@withContext Result.failure(IllegalStateException(message))
                    }

                    _transferState.value = ShareTransferState.Sending

                    val parsedUris = attachmentUris.mapValues { (_, v) -> Uri.parse(v) }
                    val resolvedAttachmentUris = resolveAttachmentUris(parsedUris)
                    if (resolvedAttachmentUris.size != attachmentUris.size) {
                        val missingCount = attachmentUris.size - resolvedAttachmentUris.size
                        val message = "Failed to resolve $missingCount attachment(s)"
                        _transferState.value = ShareTransferState.Error(message)
                        return@withContext Result.failure(Exception(message))
                    }
                    if (resolvedAttachmentUris.size > MAX_ATTACHMENTS) {
                        val message = "Too many attachments"
                        _transferState.value = ShareTransferState.Error(message)
                        return@withContext Result.failure(IllegalArgumentException(message))
                    }

                    // Build attachment info list
                    val attachmentInfos = mutableListOf<ShareAttachmentInfo>()
                    var totalAttachmentBytes = 0L
                    for ((name, uri) in resolvedAttachmentUris) {
                        val resolvedSize = resolveAttachmentSize(uri)
                        if (resolvedSize > MAX_ATTACHMENT_SIZE_BYTES) {
                            val message = "Attachment too large"
                            _transferState.value = ShareTransferState.Error(message)
                            return@withContext Result.failure(IllegalArgumentException(message))
                        }
                        val size = sanitizeAttachmentSizeForPrepare(resolvedSize)
                        totalAttachmentBytes += size
                        if (totalAttachmentBytes > MAX_TOTAL_ATTACHMENT_SIZE_BYTES) {
                            val message = "Attachments too large"
                            _transferState.value = ShareTransferState.Error(message)
                            return@withContext Result.failure(IllegalArgumentException(message))
                        }
                        val type = detectAttachmentType(name, uri)
                        attachmentInfos += ShareAttachmentInfo(name = name, type = type, size = size)
                    }
                    if (attachmentInfos.any { it.type == "unknown" }) {
                        val message = "Unsupported attachment type in memo"
                        _transferState.value = ShareTransferState.Error(message)
                        return@withContext Result.failure(Exception(message))
                    }

                    // Phase 1: Prepare
                    _transferState.value = ShareTransferState.WaitingApproval(device.name)
                    val result =
                        client.prepare(
                            device = device,
                            content = content,
                            timestamp = timestamp,
                            senderName = resolveDeviceName(),
                            attachments = attachmentInfos,
                            e2eEnabled = e2eEnabled,
                        )

                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        val message = error?.message ?: "Unknown error"
                        Timber.tag(TAG).e(error, "Prepare failed: $message")
                        _transferState.value = ShareTransferState.Error("Connection failed: $message")
                        return@withContext Result.failure(error ?: Exception("Connection failed"))
                    }

                    val prepared = result.getOrNull()
                    val sessionToken = prepared?.sessionToken
                    if (sessionToken.isNullOrBlank()) {
                        _transferState.value = ShareTransferState.Error("Transfer rejected by ${device.name}")
                        return@withContext Result.failure(Exception("Transfer rejected"))
                    }

                    // Phase 2: Transfer
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
                        _transferState.value = ShareTransferState.Error("Transfer failed")
                        Result.failure(Exception("Transfer failed"))
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Send memo failed")
                    _transferState.value = ShareTransferState.Error(e.message ?: "Unknown error")
                    Result.failure(e)
                }
            }

        override fun resetTransferState() {
            _transferState.value = ShareTransferState.Idle
        }

        // --- Receiving ---

        override fun acceptIncoming() {
            server.acceptIncoming()
            _incomingShare.value = IncomingShareState.None
        }

        override fun rejectIncoming() {
            server.rejectIncoming()
            _incomingShare.value = IncomingShareState.None
        }

        override suspend fun setLanShareE2eEnabled(enabled: Boolean) {
            dataStore.updateLanShareE2eEnabled(enabled)
        }

        override suspend fun setLanSharePairingCode(pairingCode: String) {
            val normalized = pairingCode.trim()
            val keyMaterial =
                ShareAuthUtils.deriveKeyMaterialFromPairingCode(normalized)
                    ?: throw IllegalArgumentException("Pairing code must be 6-64 characters")
            dataStore.updateLanSharePairingKeyHex(keyMaterial)
            pairingCodeInputState.value = normalized
        }

        override suspend fun clearLanSharePairingCode() {
            dataStore.updateLanSharePairingKeyHex(null)
            pairingCodeInputState.value = ""
        }

        override suspend fun setLanShareDeviceName(deviceName: String) {
            val sanitized = sanitizeDeviceName(deviceName)
            dataStore.updateLanShareDeviceName(sanitized)
            refreshServiceRegistration()
        }

        override suspend fun requiresPairingBeforeSend(): Boolean {
            val e2eEnabled = dataStore.lanShareE2eEnabled.first()
            if (!e2eEnabled) return false
            return !ShareAuthUtils.isValidKeyHex(dataStore.lanSharePairingKeyHex.first())
        }

        // --- Internal ---

        /**
         * Save a received attachment to the appropriate directory.
         * Returns the filename that was saved (for content reference mapping).
         */
        private suspend fun saveAttachmentFile(
            name: String,
            type: String,
            bytes: ByteArray,
        ): String? {
            val safeName = sanitizeAttachmentFilename(name)
            var tempFile: File? = null
            return try {
                // Create a temp file and then use FileDataSource to save properly
                tempFile = File.createTempFile("share_", "_$safeName", context.cacheDir)
                tempFile.writeBytes(bytes)
                val tempUri = Uri.fromFile(tempFile)

                when (type) {
                    "image" -> {
                        // Keep standard markdown reference format: ![image](img_xxx.ext)
                        dataSource.saveImage(tempUri)
                    }

                    "audio" -> {
                        // For audio, create the file via voice storage
                        val voiceUri = dataSource.createVoiceFile(safeName)
                        // Write the bytes to that URI
                        val output =
                            context.contentResolver.openOutputStream(voiceUri)
                                ?: throw IllegalStateException("Cannot open voice output stream")
                        output.use { out ->
                            out.write(bytes)
                        }
                        // Keep standard markdown reference format: ![voice](voice_xxx.ext)
                        safeName
                    }

                    else -> {
                        null
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to save attachment: $safeName")
                null
            } finally {
                tempFile?.delete()
            }
        }

        /**
         * Save a received memo, adapting to local filename/timestamp formats.
         * If a file for the memo's date already exists, the content is appended.
         */
        private suspend fun saveReceivedMemo(
            content: String,
            timestamp: Long,
            attachmentMappings: Map<String, String>,
        ) {
            // Replace attachment references in content with local paths
            var adaptedContent = content
            for ((originalName, newName) in attachmentMappings) {
                adaptedContent = adaptedContent.replace(originalName, newName)
            }

            // Use MemoSynchronizer.saveMemo which handles:
            // 1. Reading local storageFilenameFormat + storageTimestampFormat
            // 2. Generating appropriate filename from timestamp
            // 3. Appending to existing date file if it exists
            // 4. Updating Room DB + sync metadata
            synchronizer.saveMemo(adaptedContent, timestamp)
            // Ensure image map is refreshed after LAN receive, so UI can resolve newly saved images.
            mediaRepository.syncImageCache()

            Timber.tag(TAG).d("Received memo saved successfully")
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
            } catch (e: Exception) {
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

        private fun sanitizeAttachmentFilename(name: String): String {
            val base =
                name
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .trim()
            val sanitized =
                base
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(96)
            return if (sanitized.isBlank()) {
                "attachment_${System.currentTimeMillis()}"
            } else {
                sanitized
            }
        }

        private fun createShareClient(): LomoShareClient =
            LomoShareClient(
                context = context,
                getPairingKeyHex = { getEffectivePairingKeyHex() },
            )

        private suspend fun getEffectivePairingKeyHex(): String? = dataStore.lanSharePairingKeyHex.first()

        private suspend fun resolveDeviceName(): String {
            val custom = dataStore.lanShareDeviceName.first()
            return sanitizeDeviceName(custom) ?: getFallbackDeviceName()
        }

        private fun getFallbackDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return sanitizeDeviceName(model) ?: DEFAULT_DEVICE_NAME
        }

        private fun sanitizeDeviceName(name: String?): String? {
            val normalized =
                name
                    ?.trim()
                    ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
                    ?.replace(Regex("\\s+"), " ")
                    ?: return null
            if (normalized.isBlank()) return null
            return normalized.take(MAX_DEVICE_NAME_CHARS)
        }

        private fun refreshServiceRegistration() {
            val shouldRefresh =
                synchronized(serviceStateLock) {
                    servicesStarted && serverPort > 0
                }
            if (!shouldRefresh) return

            scope.launch {
                try {
                    val deviceName = resolveDeviceName()
                    nsdService.unregisterService()
                    nsdService.registerService(serverPort, deviceName, localUuid)
                    Timber.tag(TAG).d("Refreshed NSD service name: $deviceName")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to refresh NSD registration")
                }
            }
        }
    }
