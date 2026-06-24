package com.lomo.data.repository

import android.content.Context
import com.lomo.data.local.dao.S3LocalChangeJournalDao
import com.lomo.data.local.dao.S3SyncProtocolStateDao
import com.lomo.data.local.entity.S3LocalChangeJournalEntity
import com.lomo.data.local.entity.S3SyncProtocolStateEntity
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal const val S3_INCREMENTAL_PROTOCOL_VERSION = 4
internal const val S3_REMOTE_INDEX_FRESHNESS_INTERVAL_MS = 15 * 60_000L
internal const val S3_INCREMENTAL_RECONCILE_INTERVAL_MS = 2 * 60_000L
internal const val S3_VAULT_ROOT_AUDIT_INTERVAL_MS = 15 * 60_000L

@Serializable
data class S3SyncProtocolState(
    val protocolVersion: Int = S3_INCREMENTAL_PROTOCOL_VERSION,
    val lastSuccessfulSyncAt: Long? = null,
    val lastFastSyncAt: Long? = null,
    val lastReconcileAt: Long? = null,
    val lastFullRemoteScanAt: Long? = null,
    val indexedLocalFileCount: Int = 0,
    val indexedRemoteFileCount: Int = 0,
    val localModeFingerprint: String? = null,
    val localAuditCursor: String? = null,
    val remoteScanCursor: String? = null,
    val scanEpoch: Long = 0L,
)

interface S3SyncProtocolStateStore {
    val incrementalSyncEnabled: Boolean

    suspend fun read(): S3SyncProtocolState?

    suspend fun write(state: S3SyncProtocolState)

    suspend fun clear()
}

@Singleton
class RoomBackedS3SyncProtocolStateStore
    @Inject
    constructor(
        private val dao: S3SyncProtocolStateDao,
        @ApplicationContext private val context: Context,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : S3SyncProtocolStateStore {
        override val incrementalSyncEnabled: Boolean = true

        private val mutex = Mutex()
        private var legacySidecarDiscardChecked = false

        override suspend fun read(): S3SyncProtocolState? =
            mutex.withLock {
                discardLegacyStateSidecarIfNeeded()
                dao.getById(activeGeneration())?.toModel()
            }

        override suspend fun write(state: S3SyncProtocolState) {
            mutex.withLock {
                discardLegacyStateSidecarIfNeeded()
                dao.upsert(state.toEntity(activeGeneration()))
            }
        }

        override suspend fun clear() {
            mutex.withLock {
                discardLegacyStateSidecarIfNeeded()
                dao.clearAll(activeGeneration())
            }
        }

        private suspend fun discardLegacyStateSidecarIfNeeded() {
            if (legacySidecarDiscardChecked) {
                return
            }
            withContext(Dispatchers.IO) {
                discardUnscopedLegacyS3Sidecar(stateFile(), "S3 protocol state")
                legacySidecarDiscardChecked = true
            }
        }

        private fun stateFile(): File = File(context.filesDir, "s3_sync_protocol_state.json")

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
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

interface S3LocalChangeJournalStore {
    val incrementalSyncEnabled: Boolean

    suspend fun read(): Map<String, S3LocalChangeJournalEntry>

    suspend fun upsert(entry: S3LocalChangeJournalEntry)

    suspend fun remove(ids: Collection<String>)

    suspend fun clear()
}

@Singleton
class RoomBackedS3LocalChangeJournalStore
    @Inject
    constructor(
        private val dao: S3LocalChangeJournalDao,
        @ApplicationContext private val context: Context,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : S3LocalChangeJournalStore {
        override val incrementalSyncEnabled: Boolean = true

        private val mutex = Mutex()
        private var legacySidecarDiscardChecked = false

        override suspend fun read(): Map<String, S3LocalChangeJournalEntry> =
            mutex.withLock {
                discardLegacyJournalSidecarIfNeeded()
                dao.getAll(activeGeneration()).associate { entity -> entity.id to entity.toModel() }
            }

        override suspend fun upsert(entry: S3LocalChangeJournalEntry) {
            mutex.withLock {
                discardLegacyJournalSidecarIfNeeded()
                dao.upsert(entry.toEntity(activeGeneration()))
            }
        }

        override suspend fun remove(ids: Collection<String>) {
            if (ids.isEmpty()) {
                return
            }
            mutex.withLock {
                discardLegacyJournalSidecarIfNeeded()
                dao.deleteByIds(ids = ids, workspaceGeneration = activeGeneration())
            }
        }

        override suspend fun clear() {
            mutex.withLock {
                discardLegacyJournalSidecarIfNeeded()
                dao.clearAll(activeGeneration())
            }
        }

        private fun journalFile(): File = File(context.filesDir, "s3_local_change_journal.json")

        private suspend fun discardLegacyJournalSidecarIfNeeded() {
            if (legacySidecarDiscardChecked) {
                return
            }
            withContext(Dispatchers.IO) {
                discardUnscopedLegacyS3Sidecar(journalFile(), "S3 local journal")
                legacySidecarDiscardChecked = true
            }
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }

interface S3LocalChangeRecorder {
    suspend fun recordMemoUpsert(filename: String)

    suspend fun recordMemoDelete(filename: String)

    suspend fun recordImageUpsert(filename: String)

    suspend fun recordImageDelete(filename: String)

    suspend fun recordVoiceUpsert(filename: String)

    suspend fun recordVoiceDelete(filename: String)
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

@Module
@InstallIn(SingletonComponent::class)
internal interface S3IncrementalSyncBindingsModule {
    @Binds
    fun bindS3SyncProtocolStateStore(impl: RoomBackedS3SyncProtocolStateStore): S3SyncProtocolStateStore

    @Binds
    fun bindS3LocalChangeJournalStore(impl: RoomBackedS3LocalChangeJournalStore): S3LocalChangeJournalStore

    @Binds
    fun bindS3LocalChangeRecorder(impl: DefaultS3LocalChangeRecorder): S3LocalChangeRecorder
}

private fun discardUnscopedLegacyS3Sidecar(
    file: File,
    label: String,
) {
    if (!file.exists()) {
        return
    }
    // Behavior Contract: legacy S3 JSON sidecars predate workspace generations, so they are untrusted
    // sync state. Room rows scoped by active generation are the only authoritative source.
    if (!file.delete() && file.exists()) {
        error("Unable to delete untrusted legacy $label sidecar: ${file.absolutePath}")
    }
}

private fun S3SyncProtocolState.toEntity(workspaceGeneration: String): S3SyncProtocolStateEntity =
    S3SyncProtocolStateEntity(
        workspaceGeneration = workspaceGeneration,
        protocolVersion = protocolVersion,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        lastFastSyncAt = lastFastSyncAt,
        lastReconcileAt = lastReconcileAt,
        lastFullRemoteScanAt = lastFullRemoteScanAt,
        indexedLocalFileCount = indexedLocalFileCount,
        indexedRemoteFileCount = indexedRemoteFileCount,
        localModeFingerprint = localModeFingerprint,
        localAuditCursor = localAuditCursor,
        remoteScanCursor = remoteScanCursor,
        scanEpoch = scanEpoch,
    )

private fun S3SyncProtocolStateEntity.toModel(): S3SyncProtocolState =
    S3SyncProtocolState(
        protocolVersion = protocolVersion,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        lastFastSyncAt = lastFastSyncAt,
        lastReconcileAt = lastReconcileAt,
        lastFullRemoteScanAt = lastFullRemoteScanAt,
        indexedLocalFileCount = indexedLocalFileCount,
        indexedRemoteFileCount = indexedRemoteFileCount,
        localModeFingerprint = localModeFingerprint,
        localAuditCursor = localAuditCursor,
        remoteScanCursor = remoteScanCursor,
        scanEpoch = scanEpoch,
    )

private fun S3LocalChangeJournalEntry.toEntity(workspaceGeneration: String): S3LocalChangeJournalEntity =
    S3LocalChangeJournalEntity(
        workspaceGeneration = workspaceGeneration,
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

internal fun S3SyncProtocolState.hasFreshRemoteIndex(
    config: S3ResolvedConfig,
    now: Long = System.currentTimeMillis(),
): Boolean =
    lastFullRemoteScanAt != null &&
        now - lastFullRemoteScanAt <= config.endpointProfile.remoteIndexFreshnessIntervalMs
