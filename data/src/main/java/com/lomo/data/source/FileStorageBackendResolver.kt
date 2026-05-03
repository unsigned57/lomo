package com.lomo.data.source

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private var currentRootVfs: WorkspaceVfs? = null
        private var currentRootConfig: StorageRootConfig? = null

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

        internal suspend fun rootVfs(): WorkspaceVfs? =
            backendCacheMutex.withLock {
                resolveRootBackendsLocked()
                currentRootVfs
            }

        internal suspend fun resolvedMediaRoot(type: StorageRootType): ResolvedMediaRoot? =
            buildResolvedMediaRoot(
                rootConfig = dataStore.readStorageRootConfig(type),
                context = context,
                secureWipeBeforeDeleteEnabled = { dataStore.secureWipeBeforeDeleteEnabled.first() },
            )

        suspend fun voiceBackend(): MediaStorageBackend? {
            val configuredVoiceBackend = resolvedMediaRoot(StorageRootType.VOICE)?.backend
            return configuredVoiceBackend ?: rootMediaBackend()
        }

        private suspend fun resolveRootBackendsLocked() {
            val rootConfig = dataStore.readStorageRootConfig(StorageRootType.MAIN)
            if (currentRootConfig == rootConfig && currentMarkdownBackend != null) {
                return
            }

            val rootVfs = rootConfig.toWorkspaceVfs()
            val backend =
                rootVfs?.let {
                    VfsStorageBackend(
                        context = context,
                        rootVfs = it,
                        secureWipeBeforeDeleteEnabled = { dataStore.secureWipeBeforeDeleteEnabled.first() },
                    )
                }
            currentMarkdownBackend = backend
            currentWorkspaceBackend = backend
            currentRootVfs = rootVfs
            currentRootConfig = rootConfig
        }

        private suspend fun rootMediaBackend(): MediaStorageBackend? {
            return buildResolvedMediaRoot(
                rootConfig = dataStore.readStorageRootConfig(StorageRootType.MAIN),
                context = context,
                secureWipeBeforeDeleteEnabled = { dataStore.secureWipeBeforeDeleteEnabled.first() },
            )?.backend
        }
    }

private fun buildResolvedMediaRoot(
    rootConfig: StorageRootConfig,
    context: Context,
    secureWipeBeforeDeleteEnabled: suspend () -> Boolean,
): ResolvedMediaRoot? {
    val vfs = rootConfig.toWorkspaceVfs() ?: return null
    return ResolvedMediaRoot(
        backend =
            VfsStorageBackend(
                context = context,
                rootVfs = vfs,
                secureWipeBeforeDeleteEnabled = secureWipeBeforeDeleteEnabled,
            ),
        vfs = vfs,
        configuredUriMarker = rootConfig.configuredUri,
    )
}
