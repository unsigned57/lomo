package com.lomo.data.repository

import android.content.Context
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.entity.S3LocalChangeJournalEntity
import com.lomo.data.local.entity.S3SyncProtocolStateEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val S3_INCREMENTAL_PROTOCOL_VERSION = 2
internal const val S3_MANIFEST_FILENAME = ".lomo-sync-manifest-v2.json"
internal const val S3_MANIFEST_CONTENT_TYPE = "application/json; charset=utf-8"
internal const val S3_MANIFEST_REVISION_METADATA_KEY = "lomo-sync-revision"
internal const val S3_MANIFEST_GENERATED_AT_METADATA_KEY = "lomo-sync-generated-at"
internal const val S3_VAULT_ROOT_AUDIT_INTERVAL_MS = 15 * 60_000L

@Serializable
data class S3SyncProtocolState(
    val protocolVersion: Int = S3_INCREMENTAL_PROTOCOL_VERSION,
    val lastManifestRevision: Long? = null,
    val lastSuccessfulSyncAt: Long? = null,
    val indexedLocalFileCount: Int = 0,
    val indexedRemoteFileCount: Int = 0,
    val localModeFingerprint: String? = null,
)

interface S3SyncProtocolStateStore {
    val incrementalSyncEnabled: Boolean

    suspend fun read(): S3SyncProtocolState?

    suspend fun write(state: S3SyncProtocolState)

    suspend fun clear()
}

object DisabledS3SyncProtocolStateStore : S3SyncProtocolStateStore {
    override val incrementalSyncEnabled: Boolean = false

    override suspend fun read(): S3SyncProtocolState? = null

    override suspend fun write(state: S3SyncProtocolState) = Unit

    override suspend fun clear() = Unit
}

class InMemoryS3SyncProtocolStateStore(
    override val incrementalSyncEnabled: Boolean = true,
) : S3SyncProtocolStateStore {
    private val mutex = Mutex()
    private var state: S3SyncProtocolState? = null

    override suspend fun read(): S3SyncProtocolState? = mutex.withLock { state }

    override suspend fun write(state: S3SyncProtocolState) {
        mutex.withLock {
            this.state = state
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            state = null
        }
    }
}

@Singleton
class RoomBackedS3SyncProtocolStateStore
    @Inject
    constructor(
        private val dao: S3SyncProtocolStateDao,
        @ApplicationContext private val context: Context,
    ) : S3SyncProtocolStateStore {
        override val incrementalSyncEnabled: Boolean = true

        private val json = Json { ignoreUnknownKeys = true }
        private val mutex = Mutex()
        private var legacyMigrationChecked = false

        override suspend fun read(): S3SyncProtocolState? =
            mutex.withLock {
                migrateLegacyStateIfNeeded()
                dao.getById()?.toModel()
            }

        override suspend fun write(state: S3SyncProtocolState) {
            mutex.withLock {
                migrateLegacyStateIfNeeded()
                dao.upsert(state.toEntity())
            }
        }

        override suspend fun clear() {
            mutex.withLock {
                migrateLegacyStateIfNeeded()
                dao.clearAll()
            }
        }

        private suspend fun migrateLegacyStateIfNeeded() {
            if (legacyMigrationChecked) {
                return
            }
            withContext(Dispatchers.IO) {
                val file = stateFile()
                if (dao.getById() == null && file.exists()) {
                    runCatching {
                        json.decodeFromString(S3SyncProtocolState.serializer(), file.readText())
                    }.getOrNull()?.let { legacy ->
                        dao.upsert(legacy.toEntity())
                    }
                    file.delete()
                }
                legacyMigrationChecked = true
            }
        }

        private fun stateFile(): File = File(context.filesDir, "s3_sync_protocol_state.json")
    }

enum class S3LocalChangeKind {
    MEMO,
    IMAGE,
    VOICE,
    GENERIC,
}

enum class S3LocalChangeType {
    UPSERT,
    DELETE,
}

@Serializable
data class S3LocalChangeJournalEntry(
    val id: String,
    val kind: S3LocalChangeKind,
    val filename: String,
    val changeType: S3LocalChangeType,
    val updatedAt: Long,
) {
    fun relativePath(layout: com.lomo.data.sync.SyncDirectoryLayout): String? = legacyRelativePath(layout)

    internal fun relativePath(
        layout: com.lomo.data.sync.SyncDirectoryLayout,
        mode: S3LocalSyncMode,
    ): String? {
        return when (mode) {
            is S3LocalSyncMode.Legacy -> legacyRelativePath(layout)
            is S3LocalSyncMode.VaultRoot ->
                when (kind) {
                    S3LocalChangeKind.GENERIC -> sanitizeRelativePath(filename)
                    else ->
                        joinRelativePath(
                            mode.relativeDirectoryFor(kind),
                            sanitizeRelativePath(filename) ?: filename,
                        )
                }
        }
    }

    private fun legacyRelativePath(layout: com.lomo.data.sync.SyncDirectoryLayout): String? =
        when (kind) {
            S3LocalChangeKind.MEMO -> "$S3_ROOT/${layout.memoFolder}/$filename"
            S3LocalChangeKind.IMAGE -> "$S3_ROOT/${layout.imageFolder}/$filename"
            S3LocalChangeKind.VOICE -> "$S3_ROOT/${layout.voiceFolder}/$filename"
            S3LocalChangeKind.GENERIC -> null
        }

    companion object {
        fun generic(
            relativePath: String,
            changeType: S3LocalChangeType,
            updatedAt: Long,
        ): S3LocalChangeJournalEntry {
            val sanitized = sanitizeRelativePath(relativePath) ?: relativePath
            return S3LocalChangeJournalEntry(
                id = "GENERIC:$sanitized",
                kind = S3LocalChangeKind.GENERIC,
                filename = sanitized,
                changeType = changeType,
                updatedAt = updatedAt,
            )
        }
    }
}

@Serializable
private data class S3LocalChangeJournalSnapshot(
    val entries: List<S3LocalChangeJournalEntry> = emptyList(),
)

interface S3LocalChangeJournalStore {
    val incrementalSyncEnabled: Boolean

    suspend fun read(): Map<String, S3LocalChangeJournalEntry>

    suspend fun upsert(entry: S3LocalChangeJournalEntry)

    suspend fun remove(ids: Collection<String>)

    suspend fun clear()
}

object DisabledS3LocalChangeJournalStore : S3LocalChangeJournalStore {
    override val incrementalSyncEnabled: Boolean = false

    override suspend fun read(): Map<String, S3LocalChangeJournalEntry> = emptyMap()

    override suspend fun upsert(entry: S3LocalChangeJournalEntry) = Unit

    override suspend fun remove(ids: Collection<String>) = Unit

    override suspend fun clear() = Unit
}

class InMemoryS3LocalChangeJournalStore(
    override val incrementalSyncEnabled: Boolean = true,
) : S3LocalChangeJournalStore {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, S3LocalChangeJournalEntry>()

    override suspend fun read(): Map<String, S3LocalChangeJournalEntry> =
        mutex.withLock { entries.toMap() }

    override suspend fun upsert(entry: S3LocalChangeJournalEntry) {
        mutex.withLock {
            entries[entry.id] = entry
        }
    }

    override suspend fun remove(ids: Collection<String>) {
        mutex.withLock {
            ids.forEach(entries::remove)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            entries.clear()
        }
    }
}

@Singleton
class RoomBackedS3LocalChangeJournalStore
    @Inject
    constructor(
        private val dao: S3LocalChangeJournalDao,
        @ApplicationContext private val context: Context,
    ) : S3LocalChangeJournalStore {
        override val incrementalSyncEnabled: Boolean = true

        private val json = Json { ignoreUnknownKeys = true }
        private val mutex = Mutex()
        private var legacyMigrationChecked = false

        override suspend fun read(): Map<String, S3LocalChangeJournalEntry> =
            mutex.withLock {
                migrateLegacyJournalIfNeeded()
                dao.getAll().associate { entity -> entity.id to entity.toModel() }
            }

        override suspend fun upsert(entry: S3LocalChangeJournalEntry) {
            mutex.withLock {
                migrateLegacyJournalIfNeeded()
                dao.upsert(entry.toEntity())
            }
        }

        override suspend fun remove(ids: Collection<String>) {
            if (ids.isEmpty()) {
                return
            }
            mutex.withLock {
                migrateLegacyJournalIfNeeded()
                dao.deleteByIds(ids)
            }
        }

        override suspend fun clear() {
            mutex.withLock {
                migrateLegacyJournalIfNeeded()
                dao.clearAll()
            }
        }

        private fun journalFile(): File = File(context.filesDir, "s3_local_change_journal.json")

        private suspend fun migrateLegacyJournalIfNeeded() {
            if (legacyMigrationChecked) {
                return
            }
            withContext(Dispatchers.IO) {
                val file = journalFile()
                if (dao.getAll().isEmpty() && file.exists()) {
                    runCatching {
                        json.decodeFromString(S3LocalChangeJournalSnapshot.serializer(), file.readText()).entries
                    }.getOrDefault(emptyList())
                        .sortedBy(S3LocalChangeJournalEntry::id)
                        .forEach { entry -> dao.upsert(entry.toEntity()) }
                    file.delete()
                }
                legacyMigrationChecked = true
            }
        }
    }

interface S3LocalChangeRecorder {
    suspend fun recordMemoUpsert(filename: String)

    suspend fun recordMemoDelete(filename: String)

    suspend fun recordImageUpsert(filename: String)

    suspend fun recordImageDelete(filename: String)

    suspend fun recordVoiceUpsert(filename: String)

    suspend fun recordVoiceDelete(filename: String)
}

object NoOpS3LocalChangeRecorder : S3LocalChangeRecorder {
    override suspend fun recordMemoUpsert(filename: String) = Unit

    override suspend fun recordMemoDelete(filename: String) = Unit

    override suspend fun recordImageUpsert(filename: String) = Unit

    override suspend fun recordImageDelete(filename: String) = Unit

    override suspend fun recordVoiceUpsert(filename: String) = Unit

    override suspend fun recordVoiceDelete(filename: String) = Unit
}

@Singleton
class DefaultS3LocalChangeRecorder
    @Inject
    constructor(
        private val journalStore: S3LocalChangeJournalStore,
    ) : S3LocalChangeRecorder {
        override suspend fun recordMemoUpsert(filename: String) =
            record(kind = S3LocalChangeKind.MEMO, filename = filename, changeType = S3LocalChangeType.UPSERT)

        override suspend fun recordMemoDelete(filename: String) =
            record(kind = S3LocalChangeKind.MEMO, filename = filename, changeType = S3LocalChangeType.DELETE)

        override suspend fun recordImageUpsert(filename: String) =
            record(kind = S3LocalChangeKind.IMAGE, filename = filename, changeType = S3LocalChangeType.UPSERT)

        override suspend fun recordImageDelete(filename: String) =
            record(kind = S3LocalChangeKind.IMAGE, filename = filename, changeType = S3LocalChangeType.DELETE)

        override suspend fun recordVoiceUpsert(filename: String) =
            record(kind = S3LocalChangeKind.VOICE, filename = filename, changeType = S3LocalChangeType.UPSERT)

        override suspend fun recordVoiceDelete(filename: String) =
            record(kind = S3LocalChangeKind.VOICE, filename = filename, changeType = S3LocalChangeType.DELETE)

        private suspend fun record(
            kind: S3LocalChangeKind,
            filename: String,
            changeType: S3LocalChangeType,
        ) {
            if (!journalStore.incrementalSyncEnabled || filename.isBlank()) {
                return
            }
            journalStore.upsert(
                S3LocalChangeJournalEntry(
                    id = "$kind:$filename",
                    kind = kind,
                    filename = filename,
                    changeType = changeType,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

@Serializable
data class S3RemoteManifestEntry(
    val relativePath: String,
    val remotePath: String,
    val etag: String? = null,
    val remoteLastModified: Long? = null,
)

@Serializable
data class S3RemoteManifest(
    val protocolVersion: Int = S3_INCREMENTAL_PROTOCOL_VERSION,
    val revision: Long,
    val generatedAt: Long,
    val entries: List<S3RemoteManifestEntry>,
)

data class S3RemoteManifestMetadata(
    val revision: Long,
    val generatedAt: Long?,
)

interface S3RemoteManifestStore {
    suspend fun readMetadata(
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
    ): S3RemoteManifestMetadata?

    suspend fun read(
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
    ): S3RemoteManifest?

    suspend fun write(
        client: com.lomo.data.s3.LomoS3Client,
        config: S3ResolvedConfig,
        manifest: S3RemoteManifest,
    )

    fun build(
        remoteFiles: Map<String, RemoteS3File>,
        previousRevision: Long?,
        now: Long = System.currentTimeMillis(),
    ): S3RemoteManifest

    fun manifestKey(config: S3ResolvedConfig): String
}

@Singleton
class DefaultS3RemoteManifestStore
    @Inject
    constructor(
        private val encodingSupport: S3SyncEncodingSupport,
    ) : S3RemoteManifestStore {
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun readMetadata(
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
        ): S3RemoteManifestMetadata? =
            runCatching {
                val metadata = client.getObjectMetadata(manifestKey(config)) ?: return null
                metadata.toManifestMetadata()
            }.getOrNull()

        override suspend fun read(
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
        ): S3RemoteManifest? =
            runCatching {
                val payload = client.getObject(manifestKey(config))
                val decoded = encodingSupport.decodeContent(payload.bytes, config)
                json.decodeFromString(S3RemoteManifest.serializer(), String(decoded, StandardCharsets.UTF_8))
            }.getOrNull()

        override suspend fun write(
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            manifest: S3RemoteManifest,
        ) {
            val payload =
                json
                    .encodeToString(S3RemoteManifest.serializer(), manifest)
                    .toByteArray(StandardCharsets.UTF_8)
            client.putObject(
                key = manifestKey(config),
                bytes = encodingSupport.encodeContent(payload, config),
                contentType = S3_MANIFEST_CONTENT_TYPE,
                metadata =
                    mapOf(
                        S3_MANIFEST_REVISION_METADATA_KEY to manifest.revision.toString(),
                        S3_MANIFEST_GENERATED_AT_METADATA_KEY to manifest.generatedAt.toString(),
                    ),
            )
        }

        override fun build(
            remoteFiles: Map<String, RemoteS3File>,
            previousRevision: Long?,
            now: Long,
        ): S3RemoteManifest =
            S3RemoteManifest(
                revision = (previousRevision ?: 0L) + 1L,
                generatedAt = now,
                entries =
                    remoteFiles.values
                        .sortedBy(RemoteS3File::path)
                        .map { remote ->
                            S3RemoteManifestEntry(
                                relativePath = remote.path,
                                remotePath = remote.remotePath,
                                etag = remote.etag,
                                remoteLastModified = remote.lastModified,
                            )
                        },
            )

        override fun manifestKey(config: S3ResolvedConfig): String =
            encodingSupport.remoteKeyPrefix(config) + S3_MANIFEST_FILENAME
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface S3IncrementalSyncBindingsModule {
    @Binds
    fun bindS3SyncProtocolStateStore(impl: RoomBackedS3SyncProtocolStateStore): S3SyncProtocolStateStore

    @Binds
    fun bindS3LocalChangeJournalStore(impl: RoomBackedS3LocalChangeJournalStore): S3LocalChangeJournalStore

    @Binds
    fun bindS3LocalChangeRecorder(impl: DefaultS3LocalChangeRecorder): S3LocalChangeRecorder

    @Binds
    fun bindS3RemoteManifestStore(impl: DefaultS3RemoteManifestStore): S3RemoteManifestStore
}

private fun S3SyncProtocolState.toEntity(): S3SyncProtocolStateEntity =
    S3SyncProtocolStateEntity(
        protocolVersion = protocolVersion,
        lastManifestRevision = lastManifestRevision,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        indexedLocalFileCount = indexedLocalFileCount,
        indexedRemoteFileCount = indexedRemoteFileCount,
        localModeFingerprint = localModeFingerprint,
    )

private fun S3SyncProtocolStateEntity.toModel(): S3SyncProtocolState =
    S3SyncProtocolState(
        protocolVersion = protocolVersion,
        lastManifestRevision = lastManifestRevision,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        indexedLocalFileCount = indexedLocalFileCount,
        indexedRemoteFileCount = indexedRemoteFileCount,
        localModeFingerprint = localModeFingerprint,
    )

private fun S3LocalChangeJournalEntry.toEntity(): S3LocalChangeJournalEntity =
    S3LocalChangeJournalEntity(
        id = id,
        kind = kind.name,
        filename = filename,
        changeType = changeType.name,
        updatedAt = updatedAt,
    )

private fun S3LocalChangeJournalEntity.toModel(): S3LocalChangeJournalEntry =
    S3LocalChangeJournalEntry(
        id = id,
        kind = S3LocalChangeKind.valueOf(kind),
        filename = filename,
        changeType = S3LocalChangeType.valueOf(changeType),
        updatedAt = updatedAt,
    )

private fun com.lomo.data.s3.S3RemoteObject.toManifestMetadata(): S3RemoteManifestMetadata? {
    val revision = metadata[S3_MANIFEST_REVISION_METADATA_KEY]?.toLongOrNull() ?: return null
    return S3RemoteManifestMetadata(
        revision = revision,
        generatedAt = metadata[S3_MANIFEST_GENERATED_AT_METADATA_KEY]?.toLongOrNull() ?: lastModified,
    )
}
