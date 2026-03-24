package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileWorkspaceConfigSourceDelegate
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
        private val backendResolver: FileStorageBackendResolver,
    ) : WorkspaceConfigSource {
        override suspend fun setRoot(
            type: StorageRootType,
            pathOrUri: String,
        ) {
            if (isContentUri(pathOrUri)) {
                updateRootValues(type = type, uri = pathOrUri, path = null)
            } else {
                updateRootValues(type = type, uri = null, path = pathOrUri)
            }
        }

        override fun getRootFlow(type: StorageRootType): Flow<String?> =
            combine(readRootUriFlow(type), readRootPathFlow(type)) { uri, path -> uri ?: path }

        override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> =
            getRootFlow(type).map { uriOrPath ->
                when {
                    uriOrPath == null -> null
                    isContentUri(uriOrPath) -> withContext(Dispatchers.IO) { displayNameForUri(Uri.parse(uriOrPath)) }
                    else -> uriOrPath
                }
            }

        override suspend fun createDirectory(name: String): String =
            backendResolver.workspaceBackend()?.createDirectory(name)
                ?: throw IOException("No storage configured")

        private suspend fun updateRootValues(
            type: StorageRootType,
            uri: String?,
            path: String?,
        ) {
            when (type) {
                StorageRootType.MAIN -> {
                    dataStore.updateRootUri(uri)
                    dataStore.updateRootDirectory(path)
                }

                StorageRootType.IMAGE -> {
                    dataStore.updateImageUri(uri)
                    dataStore.updateImageDirectory(path)
                }

                StorageRootType.VOICE -> {
                    dataStore.updateVoiceUri(uri)
                    dataStore.updateVoiceDirectory(path)
                }
            }
        }

        private fun readRootUriFlow(type: StorageRootType): Flow<String?> =
            when (type) {
                StorageRootType.MAIN -> dataStore.rootUri
                StorageRootType.IMAGE -> dataStore.imageUri
                StorageRootType.VOICE -> dataStore.voiceUri
            }

        private fun readRootPathFlow(type: StorageRootType): Flow<String?> =
            when (type) {
                StorageRootType.MAIN -> dataStore.rootDirectory
                StorageRootType.IMAGE -> dataStore.imageDirectory
                StorageRootType.VOICE -> dataStore.voiceDirectory
            }

        private fun displayNameForUri(uri: Uri): String =
            try {
                DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
            } catch (_: Exception) {
                uri.lastPathSegment ?: uri.toString()
            }

        private fun isContentUri(value: String): Boolean =
            runCatching {
                java.net.URI(value).scheme.equals("content", ignoreCase = true)
            }.getOrDefault(false)
    }
