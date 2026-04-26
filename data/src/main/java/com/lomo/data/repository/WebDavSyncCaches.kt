package com.lomo.data.repository

import com.lomo.data.local.dao.WebDavLocalFingerprintDao
import com.lomo.data.local.entity.WebDavLocalFingerprintEntity
import com.lomo.data.webdav.WebDavClient
import com.lomo.data.webdav.WebDavRemoteResource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

object DisabledWebDavLocalFingerprintCache : WebDavLocalFingerprintCache {
    override suspend fun get(key: WebDavLocalFingerprintKey): String? = null

    override suspend fun put(
        key: WebDavLocalFingerprintKey,
        fingerprint: String,
    ) = Unit

    override suspend fun retain(validKeys: Set<WebDavLocalFingerprintKey>) = Unit
}

class InMemoryWebDavLocalFingerprintCache : WebDavLocalFingerprintCache {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, WebDavLocalFingerprintEntity>()

    override suspend fun get(key: WebDavLocalFingerprintKey): String? =
        mutex.withLock {
            entries[key.path]
                ?.takeIf { entity ->
                    entity.lastModified == key.lastModified &&
                        entity.size == key.size
                }?.fingerprint
        }

    override suspend fun put(
        key: WebDavLocalFingerprintKey,
        fingerprint: String,
    ) {
        mutex.withLock {
            entries[key.path] =
                WebDavLocalFingerprintEntity(
                    path = key.path,
                    lastModified = key.lastModified,
                    size = key.size,
                    fingerprint = fingerprint,
                )
        }
    }

    override suspend fun retain(validKeys: Set<WebDavLocalFingerprintKey>) {
        val validPaths = validKeys.mapTo(linkedSetOf(), WebDavLocalFingerprintKey::path)
        mutex.withLock {
            entries.keys.retainAll(validPaths)
        }
    }
}

@Singleton
class RoomBackedWebDavLocalFingerprintCache
    @Inject
    constructor(
        private val dao: WebDavLocalFingerprintDao,
    ) : WebDavLocalFingerprintCache {
        override suspend fun get(key: WebDavLocalFingerprintKey): String? =
            dao.getByPath(key.path)
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
                    path = key.path,
                    lastModified = key.lastModified,
                    size = key.size,
                    fingerprint = fingerprint,
                ),
            )
        }

        override suspend fun retain(validKeys: Set<WebDavLocalFingerprintKey>) {
            val validPaths = validKeys.mapTo(linkedSetOf(), WebDavLocalFingerprintKey::path)
            dao.deleteExcept(validPaths)
        }
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
