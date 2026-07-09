package com.lomo.data.source

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class FileStorageBackendResolver(
    private val context: Context,
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
            )?.backend
        }
    }

private fun buildResolvedMediaRoot(
    rootConfig: StorageRootConfig,
    context: Context,
): ResolvedMediaRoot? {
    val vfs = rootConfig.toWorkspaceVfs() ?: return null
    return ResolvedMediaRoot(
        backend =
            VfsStorageBackend(
                context = context,
                rootVfs = vfs,
            ),
        vfs = vfs,
        configuredUriMarker = rootConfig.configuredUri,
    )
}
