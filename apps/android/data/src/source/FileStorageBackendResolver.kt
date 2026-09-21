package com.lomo.data.source

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class FileStorageBackendResolver(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
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
                dispatcherProvider = dispatcherProvider,
            )

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
                        dispatcherProvider = dispatcherProvider,
                    )
                }
            currentMarkdownBackend = backend
            currentWorkspaceBackend = backend
            currentRootVfs = rootVfs
            currentRootConfig = rootConfig
        }

    }

private fun buildResolvedMediaRoot(
    rootConfig: StorageRootConfig,
    context: Context,
    dispatcherProvider: DispatcherProvider,
): ResolvedMediaRoot? {
    val vfs = rootConfig.toWorkspaceVfs() ?: return null
    return ResolvedMediaRoot(
        backend =
            VfsStorageBackend(
                context = context,
                rootVfs = vfs,
                dispatcherProvider = dispatcherProvider,
            ),
        vfs = vfs,
        configuredUriMarker = rootConfig.configuredUri,
    )
}
