package com.lomo.data.source

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.local.datastore.LomoDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
        ) = dataStore.updateStorageRoot(type, pathOrUri)

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
    }
