package com.lomo.data.source

import android.content.Context
import androidx.core.net.toUri
import com.lomo.data.local.datastore.LomoDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileStorageBackendResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataStore: LomoDataStore,
    ) {
        private val backendCacheMutex = Mutex()
        private var currentMarkdownBackend: MarkdownStorageBackend? = null
        private var currentWorkspaceBackend: WorkspaceConfigBackend? = null
        private var currentRootConfig: String? = null

        suspend fun markdownBackend(): MarkdownStorageBackend? =
            backendCacheMutex.withLock {
                resolveRootBackendsLocked()
                currentMarkdownBackend
            }

        suspend fun workspaceBackend(): WorkspaceConfigBackend? =
            backendCacheMutex.withLock {
                resolveRootBackendsLocked()
                currentWorkspaceBackend
            }

        suspend fun mediaBackend(type: StorageRootType): Pair<MediaStorageBackend?, String?> =
            buildMediaBackend(
                configuredUri = readRootUriFlow(type).first(),
                configuredPath = readRootPathFlow(type).first(),
            )

        suspend fun voiceBackend(): MediaStorageBackend? {
            val (configuredVoiceBackend, _) = mediaBackend(StorageRootType.VOICE)
            return configuredVoiceBackend ?: rootMediaBackend()
        }

        private suspend fun resolveRootBackendsLocked() {
            val rootUri = dataStore.rootUri.first()
            val rootDir = dataStore.rootDirectory.first()
            val configKey = rootUri ?: rootDir
            if (currentRootConfig == configKey && currentMarkdownBackend != null) {
                return
            }

            val (markdownBackend, workspaceBackend) = buildRootBackends(rootUri, rootDir)
            currentMarkdownBackend = markdownBackend
            currentWorkspaceBackend = workspaceBackend
            currentRootConfig = configKey
        }

        private fun buildRootBackends(
            rootUri: String?,
            rootDir: String?,
        ): Pair<MarkdownStorageBackend?, WorkspaceConfigBackend?> =
            when {
                rootUri != null -> {
                    val backend = SafStorageBackend(context, rootUri.toUri())
                    backend to backend
                }

                rootDir != null -> {
                    val backend = DirectStorageBackend(File(rootDir))
                    backend to backend
                }

                else -> null to null
            }

        private suspend fun rootMediaBackend(): MediaStorageBackend? {
            val rootUri = dataStore.rootUri.first()
            val rootDir = dataStore.rootDirectory.first()
            return buildMediaBackend(rootUri, rootDir).first
        }

        private fun buildMediaBackend(
            configuredUri: String?,
            configuredPath: String?,
        ): Pair<MediaStorageBackend?, String?> =
            when {
                configuredUri != null -> SafStorageBackend(context, configuredUri.toUri()) to configuredUri
                configuredPath != null -> DirectStorageBackend(File(configuredPath)) to null
                else -> null to null
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
    }
