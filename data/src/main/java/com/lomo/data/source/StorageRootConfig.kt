package com.lomo.data.source

import androidx.core.net.toUri
import com.lomo.data.local.datastore.LomoDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File

internal data class StorageRootConfig(
    val configuredUri: String?,
    val configuredPath: String?,
) {
    fun preferredLocation(): String? = configuredUri ?: configuredPath

    fun toWorkspaceVfs(): WorkspaceVfs? =
        when {
            configuredUri != null -> WorkspaceVfs.Saf(configuredUri.toUri())
            configuredPath != null -> WorkspaceVfs.Direct(File(configuredPath))
            else -> null
        }
}

internal fun LomoDataStore.storageRootConfigFlow(type: StorageRootType): Flow<StorageRootConfig> {
    val access = storageRootAccess(type)
    return combine(access.uriFlow, access.pathFlow) { uri, path -> StorageRootConfig(uri, path) }
}

internal suspend fun LomoDataStore.readStorageRootConfig(type: StorageRootType): StorageRootConfig =
    storageRootConfigFlow(type).first()

internal suspend fun LomoDataStore.updateStorageRoot(
    type: StorageRootType,
    pathOrUri: String,
) {
    val access = storageRootAccess(type)
    if (isContentStorageUri(pathOrUri)) {
        access.updateUri(pathOrUri)
        access.updatePath(null)
    } else {
        access.updateUri(null)
        access.updatePath(pathOrUri)
    }
}

internal fun isContentStorageUri(value: String): Boolean =
    runCatching {
        java.net.URI(value).scheme.equals("content", ignoreCase = true)
    }.getOrDefault(false)

private data class StorageRootAccess(
    val uriFlow: Flow<String?>,
    val pathFlow: Flow<String?>,
    val updateUri: suspend (String?) -> Unit,
    val updatePath: suspend (String?) -> Unit,
)

private fun LomoDataStore.storageRootAccess(type: StorageRootType): StorageRootAccess =
    when (type) {
        StorageRootType.MAIN ->
            StorageRootAccess(
                uriFlow = rootUri,
                pathFlow = rootDirectory,
                updateUri = ::updateRootUri,
                updatePath = ::updateRootDirectory,
            )

        StorageRootType.IMAGE ->
            StorageRootAccess(
                uriFlow = imageUri,
                pathFlow = imageDirectory,
                updateUri = ::updateImageUri,
                updatePath = ::updateImageDirectory,
            )

        StorageRootType.VOICE ->
            StorageRootAccess(
                uriFlow = voiceUri,
                pathFlow = voiceDirectory,
                updateUri = ::updateVoiceUri,
                updatePath = ::updateVoiceDirectory,
            )

        StorageRootType.SYNC_INBOX ->
            StorageRootAccess(
                uriFlow = syncInboxUri,
                pathFlow = syncInboxDirectory,
                updateUri = ::updateSyncInboxUri,
                updatePath = ::updateSyncInboxDirectory,
            )
    }
