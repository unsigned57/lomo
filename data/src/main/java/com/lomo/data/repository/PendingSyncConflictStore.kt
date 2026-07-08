package com.lomo.data.repository
import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
interface PendingSyncConflictStore {
    suspend fun readDescriptor(source: SyncBackendType): PendingSyncConflictDescriptor?
    suspend fun write(conflictSet: SyncConflictSet)
    suspend fun writeDescriptor(descriptor: PendingSyncConflictDescriptor)
    suspend fun clear(source: SyncBackendType)
}
data class PendingSyncConflictDescriptor(
    val source: SyncBackendType,
    val workspaceGeneration: String,
    val files: List<PendingSyncConflictFileDescriptor>,
    val timestamp: Long,
    val validationStatus: PendingSyncValidationStatus,
)
enum class PendingSyncValidationStatus {
    PENDING_RELOAD,
    VALIDATED,
    INVALIDATED,
}
data class PendingSyncConflictFileDescriptor(
    val relativePath: String,
    val isBinary: Boolean,
    val local: PendingSyncSideMetadata,
    val remote: PendingSyncSideMetadata,
)
data class PendingSyncSideMetadata(
    val locator: String,
    val contentHash: String?,
    val lastModified: Long?,
    val size: Long? = null,
    val etag: String? = null,
)
class RoomPendingSyncConflictStore
constructor(
        private val dao: PendingSyncConflictDao,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : PendingSyncConflictStore {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        override suspend fun readDescriptor(source: SyncBackendType): PendingSyncConflictDescriptor? {
            if (source == SyncBackendType.NONE) return null
            val generation = activeGeneration()
            val entity =
                dao.getByBackend(
                    backend = source.name,
                    workspaceGeneration = generation,
                ) ?: return null
            return entity.toConflictDescriptor(json = json, workspaceGeneration = generation)
        }
        override suspend fun write(conflictSet: SyncConflictSet) {
            if (conflictSet.source == SyncBackendType.NONE) return
            conflictSet.requireExplicitS3BinaryDescriptors()
            dao.upsert(conflictSet.toEntity(json = json, workspaceGeneration = activeGeneration()))
        }
        override suspend fun writeDescriptor(descriptor: PendingSyncConflictDescriptor) {
            if (descriptor.source == SyncBackendType.NONE) return
            dao.upsert(descriptor.toEntity(json = json, workspaceGeneration = activeGeneration()))
        }
        override suspend fun clear(source: SyncBackendType) {
            if (source == SyncBackendType.NONE) return
            dao.deleteByBackend(
                backend = source.name,
                workspaceGeneration = activeGeneration(),
            )
        }
        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }
private fun SyncConflictSet.toEntity(
    json: Json,
    workspaceGeneration: String,
): PendingSyncConflictEntity =
    PendingSyncConflictEntity(
        workspaceGeneration = workspaceGeneration,
        backend = source.name,
        timestamp = timestamp,
        payloadJson =
            json.encodeToString(
                PendingSyncConflictPayload(
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD.name,
                    files =
                        files.map { file ->
                            PendingSyncConflictFilePayload(
                                relativePath = file.relativePath,
                                isBinary = file.isBinary,
                                local =
                                    PendingSyncSideMetadataPayload(
                                        locator = file.relativePath,
                                        contentHash = file.localContent?.pendingContentHash(),
                                        lastModified = file.localLastModified,
                                        size = file.localContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                                        etag = file.localContent?.pendingContentHash(),
                                    ),
                                remote =
                                    PendingSyncSideMetadataPayload(
                                        locator = file.relativePath,
                                        contentHash = file.remoteContent?.pendingContentHash(),
                                        lastModified = file.remoteLastModified,
                                        size = file.remoteContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                                        etag = file.remoteContent?.pendingContentHash(),
                                    ),
                            )
                        },
                ),
            ),
    )
private fun SyncConflictSet.requireExplicitS3BinaryDescriptors() {
    require(source != SyncBackendType.S3 || files.none(SyncConflictFile::isBinary)) {
        "S3 binary/non-memo pending conflicts require explicit side descriptors from materialization"
    }
}
private fun PendingSyncConflictDescriptor.toEntity(
    json: Json,
    workspaceGeneration: String,
): PendingSyncConflictEntity =
    PendingSyncConflictEntity(
        workspaceGeneration = workspaceGeneration,
        backend = source.name,
        timestamp = timestamp,
        payloadJson =
            json.encodeToString(
                PendingSyncConflictPayload(
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD.name,
                    files =
                        files.map { file ->
                            PendingSyncConflictFilePayload(
                                relativePath = file.relativePath,
                                isBinary = file.isBinary,
                                local = file.local.toPayload(),
                                remote = file.remote.toPayload(),
                            )
                        },
                ),
            ),
    )
private fun PendingSyncConflictEntity.toConflictDescriptor(
    json: Json,
    workspaceGeneration: String,
): PendingSyncConflictDescriptor {
    val payload = json.decodeFromString<PendingSyncConflictPayload>(payloadJson)
    return PendingSyncConflictDescriptor(
        source = SyncBackendType.valueOf(backend),
        files =
            payload.files.map { file ->
                PendingSyncConflictFileDescriptor(
                    relativePath = file.relativePath,
                    isBinary = file.isBinary,
                    local = file.local.toModel(),
                    remote = file.remote.toModel(),
                )
            },
        timestamp = timestamp,
        workspaceGeneration = workspaceGeneration,
        validationStatus = PendingSyncValidationStatus.valueOf(payload.validationStatus),
    )
}
@Serializable
private data class PendingSyncConflictPayload(
    val schemaVersion: Int = 2,
    val validationStatus: String = PendingSyncValidationStatus.PENDING_RELOAD.name,
    val files: List<PendingSyncConflictFilePayload>,
)
@Serializable
private data class PendingSyncConflictFilePayload(
    val relativePath: String,
    val isBinary: Boolean,
    val local: PendingSyncSideMetadataPayload,
    val remote: PendingSyncSideMetadataPayload,
)
@Serializable
private data class PendingSyncSideMetadataPayload(
    val locator: String,
    val contentHash: String? = null,
    val lastModified: Long? = null,
    val size: Long? = null,
    val etag: String? = null,
)
private fun PendingSyncSideMetadataPayload.toModel(): PendingSyncSideMetadata =
    PendingSyncSideMetadata(
        locator = locator,
        contentHash = contentHash,
        lastModified = lastModified,
        size = size,
        etag = etag,
    )
private fun PendingSyncSideMetadata.toPayload(): PendingSyncSideMetadataPayload =
    PendingSyncSideMetadataPayload(
        locator = locator,
        contentHash = contentHash,
        lastModified = lastModified,
        size = size,
        etag = etag,
    )
private fun String.pendingContentHash(): String =
    toByteArray(Charsets.UTF_8).md5Hex()
