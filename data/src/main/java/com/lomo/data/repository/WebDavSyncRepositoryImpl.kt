package com.lomo.data.repository

import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.sync.SyncLayoutMigration
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.data.webdav.WebDavClientFactory
import com.lomo.data.webdav.WebDavCredentialStore
import com.lomo.data.webdav.WebDavEndpointResolver
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncDirection
import com.lomo.domain.model.WebDavSyncReason
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.repository.WebDavSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialStore: WebDavCredentialStore,
        private val endpointResolver: WebDavEndpointResolver,
        private val clientFactory: WebDavClientFactory,
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val localMediaSyncStore: LocalMediaSyncStore,
        private val metadataDao: WebDavSyncMetadataDao,
        private val memoSynchronizer: MemoSynchronizer,
        private val planner: WebDavSyncPlanner,
    ) : WebDavSyncRepository {
        private val syncState = MutableStateFlow<WebDavSyncState>(WebDavSyncState.Idle)
        private val syncGuard = AtomicBoolean(false)

        override fun isWebDavSyncEnabled(): Flow<Boolean> = dataStore.webDavSyncEnabled

        override fun getProvider(): Flow<WebDavProvider> = dataStore.webDavProvider.map(::webDavProviderFromPreference)

        override fun getBaseUrl(): Flow<String?> = dataStore.webDavBaseUrl

        override fun getEndpointUrl(): Flow<String?> = dataStore.webDavEndpointUrl

        override fun getUsername(): Flow<String?> = dataStore.webDavUsername

        override fun getAutoSyncEnabled(): Flow<Boolean> = dataStore.webDavAutoSyncEnabled

        override fun getAutoSyncInterval(): Flow<String> = dataStore.webDavAutoSyncInterval

        override fun getSyncOnRefreshEnabled(): Flow<Boolean> = dataStore.webDavSyncOnRefresh

        override fun observeLastSyncTimeMillis(): Flow<Long?> = dataStore.webDavLastSyncTime.map { stored -> stored.takeIf { it > 0L } }

        override suspend fun setWebDavSyncEnabled(enabled: Boolean) {
            dataStore.updateWebDavSyncEnabled(enabled)
        }

        override suspend fun setProvider(provider: WebDavProvider) {
            dataStore.updateWebDavProvider(provider.preferenceValue)
        }

        override suspend fun setBaseUrl(url: String) {
            dataStore.updateWebDavBaseUrl(url.trim())
        }

        override suspend fun setEndpointUrl(url: String) {
            dataStore.updateWebDavEndpointUrl(url.trim())
        }

        override suspend fun setUsername(username: String) {
            val normalized = username.trim()
            dataStore.updateWebDavUsername(normalized)
            credentialStore.setUsername(normalized)
        }

        override suspend fun setPassword(password: String) {
            credentialStore.setPassword(password.trim())
        }

        override suspend fun isPasswordConfigured(): Boolean = !credentialStore.getPassword().isNullOrBlank()

        override suspend fun setAutoSyncEnabled(enabled: Boolean) {
            dataStore.updateWebDavAutoSyncEnabled(enabled)
        }

        override suspend fun setAutoSyncInterval(interval: String) {
            dataStore.updateWebDavAutoSyncInterval(interval)
        }

        override suspend fun setSyncOnRefreshEnabled(enabled: Boolean) {
            dataStore.updateWebDavSyncOnRefresh(enabled)
        }

        override suspend fun sync(): WebDavSyncResult {
            if (!syncGuard.compareAndSet(false, true)) {
                return WebDavSyncResult.Success("WebDAV sync already in progress")
            }
            try {
                return performSync()
            } finally {
                syncGuard.set(false)
            }
        }

        override suspend fun getStatus(): WebDavSyncStatus {
            val config = resolveConfig() ?: return WebDavSyncStatus(0, 0, 0, null)
            val layout = SyncDirectoryLayout.resolve(dataStore)
            return runWebDavIo {
                syncState.value = WebDavSyncState.Listing
                val client = clientFactory.create(config.endpointUrl, config.username, config.password)
                client.ensureDirectory("")
                ensureRemoteDirectories(client, layout)
                val localFiles = localFiles(layout)
                val remoteFiles = remoteFiles(client, layout)
                val metadata = metadataDao.getAll().associateBy { it.relativePath }
                val plan = planner.plan(localFiles, remoteFiles, metadata)
                WebDavSyncStatus(
                    remoteFileCount = remoteFiles.size,
                    localFileCount = localFiles.size,
                    pendingChanges = plan.pendingChanges,
                    lastSyncTime = dataStore.webDavLastSyncTime.first().takeIf { it > 0L },
                )
            }
        }

        override suspend fun testConnection(): WebDavSyncResult {
            val config = resolveConfig() ?: return notConfiguredResult()
            return runCatching {
                runWebDavIo {
                    clientFactory.create(config.endpointUrl, config.username, config.password).testConnection()
                }
                WebDavSyncResult.Success("WebDAV connection successful")
            }.getOrElse(::mapConnectionTestError)
        }

        override fun syncState(): Flow<WebDavSyncState> = syncState

        private suspend fun performSync(): WebDavSyncResult {
            val config = resolveConfig() ?: return notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(dataStore)
            return try {
                val result =
                    runWebDavIo {
                        syncState.value = WebDavSyncState.Initializing
                        val client = clientFactory.create(config.endpointUrl, config.username, config.password)
                        client.ensureDirectory("")
                        ensureRemoteDirectories(client, layout)
                        SyncLayoutMigration.migrateWebDavRemote(client, layout)

                        syncState.value = WebDavSyncState.Listing
                        val localFiles = localFiles(layout)
                        val remoteFiles =
                            try {
                                remoteFiles(client, layout)
                            } catch (error: Exception) {
                                throw IllegalStateException(
                                    "Failed to list remote WebDAV files: ${error.message ?: "unknown error"}",
                                    error,
                                )
                            }
                        val existingMetadata = metadataDao.getAll().associateBy { it.relativePath }
                        val plan = planner.plan(localFiles, remoteFiles, existingMetadata)
                        val actionOutcomes = mutableMapOf<String, Pair<WebDavSyncDirection, WebDavSyncReason>>()
                        val failedPaths = mutableListOf<String>()
                        var localChanged = false
                        var remoteChanged = false

                        plan.actions.forEach { action ->
                            try {
                                when (action.direction) {
                                    WebDavSyncDirection.UPLOAD -> {
                                        syncState.value = WebDavSyncState.Uploading
                                        val bytes =
                                            if (isMemoPath(action.path, layout)) {
                                                val memoFilename = extractMemoFilename(action.path, layout)
                                                val content =
                                                    markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, memoFilename)
                                                if (content == null) {
                                                    Timber.w("Local memo missing during upload: %s, skipping", action.path)
                                                    return@forEach
                                                }
                                                content.toByteArray(StandardCharsets.UTF_8)
                                            } else {
                                                localMediaSyncStore.readBytes(action.path, layout)
                                            }
                                        remoteChanged = true
                                        client.put(
                                            path = action.path,
                                            bytes = bytes,
                                            contentType = contentTypeForPath(action.path, layout),
                                            lastModifiedHint = localFiles[action.path]?.lastModified,
                                        )
                                    }

                                    WebDavSyncDirection.DOWNLOAD -> {
                                        syncState.value = WebDavSyncState.Downloading
                                        val remoteFile = client.get(action.path)
                                        localChanged = true
                                        if (isMemoPath(action.path, layout)) {
                                            val memoFilename = extractMemoFilename(action.path, layout)
                                            markdownStorageDataSource.saveFileIn(
                                                directory = MemoDirectoryType.MAIN,
                                                filename = memoFilename,
                                                content = String(remoteFile.bytes, StandardCharsets.UTF_8),
                                            )
                                        } else {
                                            localMediaSyncStore.writeBytes(action.path, remoteFile.bytes, layout)
                                        }
                                    }

                                    WebDavSyncDirection.DELETE_LOCAL -> {
                                        syncState.value = WebDavSyncState.Deleting
                                        localChanged = true
                                        if (isMemoPath(action.path, layout)) {
                                            val memoFilename = extractMemoFilename(action.path, layout)
                                            markdownStorageDataSource.deleteFileIn(MemoDirectoryType.MAIN, memoFilename)
                                        } else {
                                            localMediaSyncStore.delete(action.path, layout)
                                        }
                                    }

                                    WebDavSyncDirection.DELETE_REMOTE -> {
                                        syncState.value = WebDavSyncState.Deleting
                                        remoteChanged = true
                                        client.delete(action.path)
                                    }

                                    WebDavSyncDirection.NONE -> {
                                        Unit
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (error: Exception) {
                                val operation =
                                    when (action.direction) {
                                        WebDavSyncDirection.UPLOAD -> "upload"
                                        WebDavSyncDirection.DOWNLOAD -> "download"
                                        WebDavSyncDirection.DELETE_LOCAL -> "delete local"
                                        WebDavSyncDirection.DELETE_REMOTE -> "delete remote"
                                        WebDavSyncDirection.NONE -> "sync"
                                    }
                                Timber.e(error, "Failed to %s %s", operation, action.path)
                                failedPaths += action.path
                                return@forEach
                            }
                            actionOutcomes[action.path] = action.direction to action.reason
                        }

                        persistMetadata(
                            client = client,
                            layout = layout,
                            localFiles = localFiles,
                            remoteFiles = remoteFiles,
                            actionOutcomes = actionOutcomes,
                            localChanged = localChanged,
                            remoteChanged = remoteChanged,
                        )

                        if (failedPaths.isNotEmpty()) {
                            val summary = "WebDAV sync partially failed: ${failedPaths.size} file(s) failed: ${failedPaths.joinToString()}"
                            WebDavSyncResult.Error(
                                message = summary,
                                outcomes = plan.actions.map { it.toOutcome() },
                            )
                        } else {
                            WebDavSyncResult.Success(
                                message = if (plan.actions.isEmpty()) "WebDAV already up to date" else "WebDAV sync completed",
                                outcomes = plan.actions.map { it.toOutcome() },
                            )
                        }
                    }

                val hasSuccessfulActions =
                    result is WebDavSyncResult.Success ||
                        (result is WebDavSyncResult.Error && result.outcomes.isNotEmpty())

                if (hasSuccessfulActions) {
                    try {
                        memoSynchronizer.refresh()
                        val now = System.currentTimeMillis()
                        dataStore.updateWebDavLastSyncTime(now)
                        when (result) {
                            is WebDavSyncResult.Success -> {
                                syncState.value = WebDavSyncState.Success(now, result.message)
                            }

                            is WebDavSyncResult.Error -> {
                                syncState.value = WebDavSyncState.Error(result.message, now)
                            }

                            WebDavSyncResult.NotConfigured -> {
                                Unit
                            }
                        }
                        result
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val outcomes =
                            when (result) {
                                is WebDavSyncResult.Success -> result.outcomes
                                is WebDavSyncResult.Error -> result.outcomes
                                WebDavSyncResult.NotConfigured -> emptyList()
                            }
                        val message = "WebDAV sync completed but memo refresh failed: ${error.message ?: "unknown error"}"
                        syncState.value = WebDavSyncState.Error(message, System.currentTimeMillis())
                        WebDavSyncResult.Error(message, error, outcomes)
                    }
                } else {
                    result
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mapError(error)
            }
        }

        private fun ensureRemoteDirectories(
            client: com.lomo.data.webdav.WebDavClient,
            layout: SyncDirectoryLayout,
        ) {
            client.ensureDirectory(WEBDAV_ROOT)
            for (folder in layout.distinctFolders) {
                client.ensureDirectory("$WEBDAV_ROOT/$folder")
            }
        }

        private suspend fun persistMetadata(
            client: com.lomo.data.webdav.WebDavClient,
            layout: SyncDirectoryLayout,
            localFiles: Map<String, LocalWebDavFile>,
            remoteFiles: Map<String, RemoteWebDavFile>,
            actionOutcomes: Map<String, Pair<WebDavSyncDirection, WebDavSyncReason>>,
            localChanged: Boolean,
            remoteChanged: Boolean,
        ) {
            val syncedLocalFiles =
                if (localChanged) {
                    localFiles(layout)
                } else {
                    localFiles
                }
            val syncedRemoteFiles =
                if (remoteChanged) {
                    try {
                        remoteFiles(client, layout)
                    } catch (error: Exception) {
                        throw IllegalStateException(
                            "Failed to reload remote WebDAV files after sync: ${error.message ?: "unknown error"}",
                            error,
                        )
                    }
                } else {
                    remoteFiles
                }
            val now = System.currentTimeMillis()
            val entities =
                syncedLocalFiles.keys
                    .intersect(syncedRemoteFiles.keys)
                    .sorted()
                    .mapNotNull { path ->
                        val local = syncedLocalFiles[path] ?: return@mapNotNull null
                        val remote = syncedRemoteFiles[path] ?: return@mapNotNull null
                        val outcome = actionOutcomes[path]
                        WebDavSyncMetadataEntity(
                            relativePath = path,
                            remotePath = path,
                            etag = remote.etag,
                            remoteLastModified = remote.lastModified,
                            localLastModified = local.lastModified,
                            lastSyncedAt = now,
                            lastResolvedDirection = outcome?.first?.name ?: WebDavSyncMetadataEntity.NONE,
                            lastResolvedReason = outcome?.second?.name ?: WebDavSyncMetadataEntity.UNCHANGED,
                        )
                    }
            metadataDao.replaceAll(entities)
        }

        private suspend fun localFiles(layout: SyncDirectoryLayout): Map<String, LocalWebDavFile> {
            val memoPrefix = "$WEBDAV_ROOT/${layout.memoFolder}/"
            val memoFiles =
                markdownStorageDataSource
                    .listMetadataIn(MemoDirectoryType.MAIN)
                    .filter { it.filename.endsWith(MEMO_SUFFIX) }
                    .associate { metadata ->
                        val remotePath = "$memoPrefix${metadata.filename}"
                        remotePath to LocalWebDavFile(remotePath, metadata.lastModified)
                    }
            val mediaFiles =
                localMediaSyncStore
                    .listFiles(layout)
                    .mapKeys { (path, _) -> "$WEBDAV_ROOT/$path" }
                    .mapValues { (path, metadata) ->
                        LocalWebDavFile(path, metadata.lastModified)
                    }
            return memoFiles + mediaFiles
        }

        private fun remoteFiles(
            client: com.lomo.data.webdav.WebDavClient,
            layout: SyncDirectoryLayout,
        ): Map<String, RemoteWebDavFile> {
            val listed = mutableListOf<WebDavRemoteResource>()
            val visitedFolders = mutableSetOf<String>()
            for (folder in layout.distinctFolders) {
                val remotePath = "$WEBDAV_ROOT/$folder"
                if (visitedFolders.add(remotePath)) {
                    listed.addAll(client.list(remotePath).filter { !it.isDirectory })
                }
            }
            return listed.associate(::toRemoteEntry)
        }

        private fun toRemoteEntry(resource: WebDavRemoteResource): Pair<String, RemoteWebDavFile> =
            resource.path to
                RemoteWebDavFile(
                    path = resource.path,
                    etag = resource.etag,
                    lastModified = resource.lastModified,
                )

        private suspend fun resolveConfig(): ResolvedConfig? {
            val enabled = dataStore.webDavSyncEnabled.first()
            if (!enabled) {
                syncState.value = WebDavSyncState.NotConfigured
                return null
            }

            val provider = webDavProviderFromPreference(dataStore.webDavProvider.first())
            val baseUrl = dataStore.webDavBaseUrl.first()
            val endpointUrl = dataStore.webDavEndpointUrl.first()
            val username = (dataStore.webDavUsername.first() ?: credentialStore.getUsername())?.trim().orEmpty()
            val password = credentialStore.getPassword()?.trim().orEmpty()
            if (username.isBlank() || password.isBlank()) {
                syncState.value = WebDavSyncState.NotConfigured
                return null
            }
            val resolvedEndpoint = endpointResolver.resolve(provider, baseUrl, endpointUrl, username)
            if (resolvedEndpoint.isNullOrBlank()) {
                syncState.value = WebDavSyncState.NotConfigured
                return null
            }
            return ResolvedConfig(
                provider = provider,
                endpointUrl = resolvedEndpoint,
                username = username,
                password = password,
            )
        }

        private fun notConfiguredResult(): WebDavSyncResult {
            syncState.value = WebDavSyncState.NotConfigured
            return WebDavSyncResult.NotConfigured
        }

        private fun mapError(error: Throwable): WebDavSyncResult.Error {
            val message =
                when (error) {
                    is CancellationException -> throw error
                    else -> error.message?.takeIf { it.isNotBlank() } ?: "WebDAV sync failed"
                }
            Timber.e(error, "WebDAV sync failed")
            syncState.value = WebDavSyncState.Error(message, System.currentTimeMillis())
            return WebDavSyncResult.Error(message, error)
        }

        private fun mapConnectionTestError(error: Throwable): WebDavSyncResult.Error {
            val message =
                when (error) {
                    is CancellationException -> throw error
                    else -> error.message?.takeIf { it.isNotBlank() } ?: "WebDAV connection test failed"
                }
            Timber.e(error, "WebDAV connection test failed")
            return WebDavSyncResult.Error(message, error)
        }

        private fun isMemoPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): Boolean {
            val memoPrefix = "$WEBDAV_ROOT/${layout.memoFolder}/"
            return path.startsWith(memoPrefix) && path.endsWith(MEMO_SUFFIX)
        }

        /**
         * Extracts the bare memo filename (e.g. `2025-01-01.md`) from the full
         * WebDAV remote path (e.g. `lomo/memos/2025-01-01.md`).
         */
        private fun extractMemoFilename(
            path: String,
            layout: SyncDirectoryLayout,
        ): String {
            val memoPrefix = "$WEBDAV_ROOT/${layout.memoFolder}/"
            return path.removePrefix(memoPrefix)
        }

        private fun contentTypeForPath(
            path: String,
            layout: SyncDirectoryLayout,
        ): String =
            if (isMemoPath(path, layout)) {
                MARKDOWN_CONTENT_TYPE
            } else {
                localMediaSyncStore.contentTypeForPath(path, layout)
            }

        private suspend fun <T> runWebDavIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

        private data class ResolvedConfig(
            val provider: WebDavProvider,
            val endpointUrl: String,
            val username: String,
            val password: String,
        )

        private companion object {
            private const val MEMO_SUFFIX = ".md"
            private const val MARKDOWN_CONTENT_TYPE = "text/markdown; charset=utf-8"
            private const val WEBDAV_ROOT = "lomo"
        }
    }

private val WebDavProvider.preferenceValue: String
    get() = name.lowercase()

private fun webDavProviderFromPreference(value: String): WebDavProvider =
    WebDavProvider.entries.firstOrNull { it.preferenceValue == value.lowercase() } ?: WebDavProvider.NUTSTORE
