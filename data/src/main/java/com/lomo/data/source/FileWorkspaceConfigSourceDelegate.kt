package com.lomo.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException


class FileWorkspaceConfigSourceDelegate(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val backendResolver: FileStorageBackendResolver,
) : WorkspaceConfigSource {
        override suspend fun setRoot(
            type: StorageRootType,
            pathOrUri: String,
        ) {
            dataStore.updateStorageRoot(type, pathOrUri)
            releaseOrphanedTreePermissions()
        }

        override fun getRootFlow(type: StorageRootType): Flow<String?> =
            dataStore.storageRootConfigFlow(type).map(StorageRootConfig::preferredLocation)

        override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
            getRootFlow(type).map { uriOrPath ->
                when {
                    uriOrPath == null -> null
                    isContentStorageUri(uriOrPath) ->
                        withContext(Dispatchers.IO) {
                            displayNameForUri(uriOrPath.toUri())
                        }
                    else -> uriOrPath
                }
            }

        override suspend fun createDirectory(name: String): String =
            backendResolver.workspaceBackend()?.createDirectory(name)
                ?: throw IOException("No storage configured")

        private fun displayNameForUri(uri: Uri): String =
            try {
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
            } catch (_: Exception) {
                uri.lastPathSegment ?: uri.toString()
            }

        /**
         * Releases persisted SAF tree permissions that no longer back any configured storage slot.
         *
         * Re-picking a folder leaves the previous folder's grant persisted. Without pruning, repeated
         * re-picks accumulate grants and can push the app past the system's persisted-permission cap,
         * after which Android silently evicts the oldest grant — potentially the active workspace root,
         * so writes start failing. Only grants absent from every configured slot are released, so an
         * in-use folder (including ones shared across slots) is never affected.
         */
        private suspend fun releaseOrphanedTreePermissions() {
            val activeUris =
                setOfNotNull(
                    dataStore.rootUri.first(),
                    dataStore.imageUri.first(),
                    dataStore.voiceUri.first(),
                    dataStore.syncInboxUri.first(),
                    dataStore.s3LocalSyncDirectory.first(),
                ).map(String::trim).filter(String::isNotEmpty).toSet()

            val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.persistedUriPermissions.forEach { permission ->
                if (permission.uri.toString() in activeUris) return@forEach
                // behavior-contract: silent-result-ok: a grant we no longer hold is already gone
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(permission.uri, releaseFlags)
                }
            }
        }
    }
