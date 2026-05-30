package com.lomo.data.repository

import com.lomo.data.local.dao.WebDavLocalFingerprintDao
import com.lomo.data.local.entity.WebDavLocalFingerprintEntity
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

data class WebDavLocalFingerprintKey(
    val path: String,
    val lastModified: Long,
    val size: Long?,
)

interface WebDavLocalFingerprintCache {
    suspend fun get(key: WebDavLocalFingerprintKey): String?

    suspend fun put(
        key: WebDavLocalFingerprintKey,
        fingerprint: String,
    )

    suspend fun retain(validKeys: Set<WebDavLocalFingerprintKey>)
}

@Singleton
class RoomBackedWebDavLocalFingerprintCache
    @Inject
    constructor(
        private val dao: WebDavLocalFingerprintDao,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : WebDavLocalFingerprintCache {
        override suspend fun get(key: WebDavLocalFingerprintKey): String? =
            dao.getByPath(path = key.path, workspaceGeneration = activeGeneration())
                ?.takeIf { entity ->
                    entity.lastModified == key.lastModified &&
                        entity.size == key.size
                }?.fingerprint

        override suspend fun put(
            key: WebDavLocalFingerprintKey,
            fingerprint: String,
        ) {
            dao.upsert(
                WebDavLocalFingerprintEntity(
                    workspaceGeneration = activeGeneration(),
                    path = key.path,
                    lastModified = key.lastModified,
                    size = key.size,
                    fingerprint = fingerprint,
                ),
            )
        }

        override suspend fun retain(validKeys: Set<WebDavLocalFingerprintKey>) {
            val validPaths = validKeys.mapTo(linkedSetOf(), WebDavLocalFingerprintKey::path)
            dao.deleteExcept(paths = validPaths, workspaceGeneration = activeGeneration())
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }

@Singleton
class WebDavRemoteListingCache
    @Inject
    constructor() {
        private val lock = Any()
        private val entries = mutableMapOf<WebDavRemoteListingKey, WebDavRemoteListingCacheEntry>()

        fun getOrLoad(
            client: WebDavClient,
            path: String,
            loader: () -> List<WebDavRemoteResource>,
        ): List<WebDavRemoteResource> {
            val key = WebDavRemoteListingKey(System.identityHashCode(client), path)
            val now = System.currentTimeMillis()
            synchronized(lock) {
                entries[key]
                    ?.takeIf { entry -> now - entry.cachedAt <= WEBDAV_REMOTE_LISTING_CACHE_TTL_MS }
                    ?.let { entry -> return entry.resources }
            }
            val loaded = loader()
            synchronized(lock) {
                entries[key] = WebDavRemoteListingCacheEntry(resources = loaded, cachedAt = now)
            }
            return loaded
        }

        fun invalidate(
            client: WebDavClient,
            path: String,
        ) {
            synchronized(lock) {
                entries.remove(WebDavRemoteListingKey(System.identityHashCode(client), path))
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface WebDavSyncCacheBindingsModule {
    @Binds
    fun bindWebDavLocalFingerprintCache(impl: RoomBackedWebDavLocalFingerprintCache): WebDavLocalFingerprintCache
}

private data class WebDavRemoteListingKey(
    val clientId: Int,
    val path: String,
)

private data class WebDavRemoteListingCacheEntry(
    val resources: List<WebDavRemoteResource>,
    val cachedAt: Long,
)

private const val WEBDAV_REMOTE_LISTING_CACHE_TTL_MS = 10_000L
