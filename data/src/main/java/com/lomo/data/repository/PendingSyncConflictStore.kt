package com.lomo.data.repository

import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PendingSyncConflictStore {
    suspend fun read(source: SyncBackendType): SyncConflictSet?

    suspend fun write(conflictSet: SyncConflictSet)

    suspend fun clear(source: SyncBackendType)
}

@Singleton
class RoomPendingSyncConflictStore
    @Inject
    constructor(
        private val dao: PendingSyncConflictDao,
    ) : PendingSyncConflictStore {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        override suspend fun read(source: SyncBackendType): SyncConflictSet? {
            if (source == SyncBackendType.NONE) return null
            return dao.getByBackend(source.name)?.toConflictSet(json)
        }

        override suspend fun write(conflictSet: SyncConflictSet) {
            if (conflictSet.source == SyncBackendType.NONE) return
            dao.upsert(conflictSet.toEntity(json))
        }

        override suspend fun clear(source: SyncBackendType) {
            if (source == SyncBackendType.NONE) return
            dao.deleteByBackend(source.name)
        }
    }

object DisabledPendingSyncConflictStore : PendingSyncConflictStore {
    override suspend fun read(source: SyncBackendType): SyncConflictSet? = null

    override suspend fun write(conflictSet: SyncConflictSet) = Unit

    override suspend fun clear(source: SyncBackendType) = Unit
}

class InMemoryPendingSyncConflictStore : PendingSyncConflictStore {
    private val entries = linkedMapOf<SyncBackendType, SyncConflictSet>()

    override suspend fun read(source: SyncBackendType): SyncConflictSet? = entries[source]

    override suspend fun write(conflictSet: SyncConflictSet) {
        entries[conflictSet.source] = conflictSet
    }

    override suspend fun clear(source: SyncBackendType) {
        entries.remove(source)
    }
}

private fun SyncConflictSet.toEntity(json: Json): PendingSyncConflictEntity =
    PendingSyncConflictEntity(
        backend = source.name,
        sessionKind = sessionKind.name,
        timestamp = timestamp,
        payloadJson =
            json.encodeToString(
                PendingSyncConflictPayload(
                    files =
                        files.map { file ->
                            PendingSyncConflictFilePayload(
                                relativePath = file.relativePath,
                                localContent = file.localContent,
                                remoteContent = file.remoteContent,
                                isBinary = file.isBinary,
                                localLastModified = file.localLastModified,
                                remoteLastModified = file.remoteLastModified,
                            )
                        },
                ),
            ),
    )

private fun PendingSyncConflictEntity.toConflictSet(json: Json): SyncConflictSet {
    val payload = json.decodeFromString<PendingSyncConflictPayload>(payloadJson)
    return SyncConflictSet(
        source = SyncBackendType.valueOf(backend),
        files =
            payload.files.map { file ->
                SyncConflictFile(
                    relativePath = file.relativePath,
                    localContent = file.localContent,
                    remoteContent = file.remoteContent,
                    isBinary = file.isBinary,
                    localLastModified = file.localLastModified,
                    remoteLastModified = file.remoteLastModified,
                )
            },
        timestamp = timestamp,
        sessionKind = SyncConflictSessionKind.valueOf(sessionKind),
    )
}

@Serializable
private data class PendingSyncConflictPayload(
    val files: List<PendingSyncConflictFilePayload>,
)

@Serializable
private data class PendingSyncConflictFilePayload(
    val relativePath: String,
    val localContent: String?,
    val remoteContent: String?,
    val isBinary: Boolean,
    val localLastModified: Long? = null,
    val remoteLastModified: Long? = null,
)
