package com.lomo.data.repository

import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.entity.PendingSyncReviewEntity
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PendingSyncReviewStore {
    suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor?

    suspend fun write(review: SyncReviewSession)

    suspend fun writeDescriptor(descriptor: PendingSyncReviewDescriptor)

    suspend fun clear(source: SyncBackendType)
}

data class PendingSyncReviewDescriptor(
    val source: SyncBackendType,
    val workspaceGeneration: String,
    val kind: SyncReviewSessionKind,
    val items: List<PendingSyncReviewItemDescriptor>,
    val timestamp: Long,
    val validationStatus: PendingSyncValidationStatus,
)

data class PendingSyncReviewItemDescriptor(
    val relativePath: String,
    val isBinary: Boolean,
    val local: PendingSyncSideMetadata,
    val incoming: PendingSyncSideMetadata,
    val state: SyncReviewItemState,
    val message: String? = null,
)

@Singleton
class RoomPendingSyncReviewStore
    @Inject
    constructor(
        private val dao: PendingSyncReviewDao,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : PendingSyncReviewStore {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? {
            if (source == SyncBackendType.NONE) return null
            val generation = activeGeneration()
            return dao
                .getByBackend(
                    backend = source.name,
                    workspaceGeneration = generation,
                )?.toReviewDescriptor(json = json, workspaceGeneration = generation)
        }

        override suspend fun write(review: SyncReviewSession) {
            if (review.source == SyncBackendType.NONE) return
            review.requireExplicitS3BinaryDescriptors()
            dao.upsert(review.toEntity(json = json, workspaceGeneration = activeGeneration()))
        }

        override suspend fun writeDescriptor(descriptor: PendingSyncReviewDescriptor) {
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

private fun SyncReviewSession.toEntity(
    json: Json,
    workspaceGeneration: String,
): PendingSyncReviewEntity =
    PendingSyncReviewEntity(
        workspaceGeneration = workspaceGeneration,
        backend = source.name,
        reviewKind = kind.name,
        timestamp = timestamp,
        payloadJson =
            json.encodeToString(
                PendingSyncReviewPayload(
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD.name,
                    items =
                        items.map { item ->
                            PendingSyncReviewItemPayload(
                                relativePath = item.relativePath,
                                isBinary = item.isBinary,
                                local =
                                    PendingSyncReviewSideMetadataPayload(
                                        locator = item.relativePath,
                                        contentHash = item.localContent?.pendingContentHash(),
                                        lastModified = item.localLastModified,
                                        size = item.localContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                                        etag = item.localContent?.pendingContentHash(),
                                    ),
                                incoming =
                                    PendingSyncReviewSideMetadataPayload(
                                        locator = item.relativePath,
                                        contentHash = item.incomingContent?.pendingContentHash(),
                                        lastModified = item.incomingLastModified,
                                        size = item.incomingContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                                        etag = item.incomingContent?.pendingContentHash(),
                                    ),
                                state = item.state.name,
                                message = item.message,
                            )
                        },
                ),
            ),
    )

private fun SyncReviewSession.requireExplicitS3BinaryDescriptors() {
    require(source != SyncBackendType.S3 || items.none(SyncReviewItem::isBinary)) {
        "S3 binary/non-memo pending reviews require explicit side descriptors from materialization"
    }
}

private fun PendingSyncReviewDescriptor.toEntity(
    json: Json,
    workspaceGeneration: String,
): PendingSyncReviewEntity =
    PendingSyncReviewEntity(
        workspaceGeneration = workspaceGeneration,
        backend = source.name,
        reviewKind = kind.name,
        timestamp = timestamp,
        payloadJson =
            json.encodeToString(
                PendingSyncReviewPayload(
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD.name,
                    items =
                        items.map { item ->
                            PendingSyncReviewItemPayload(
                                relativePath = item.relativePath,
                                isBinary = item.isBinary,
                                local = item.local.toReviewPayload(),
                                incoming = item.incoming.toReviewPayload(),
                                state = item.state.name,
                                message = item.message,
                            )
                        },
                ),
            ),
    )

private fun PendingSyncReviewEntity.toReviewDescriptor(
    json: Json,
    workspaceGeneration: String,
): PendingSyncReviewDescriptor {
    val payload = json.decodeFromString<PendingSyncReviewPayload>(payloadJson)
    return PendingSyncReviewDescriptor(
        source = SyncBackendType.valueOf(backend),
        items =
            payload.items.map { item ->
                PendingSyncReviewItemDescriptor(
                    relativePath = item.relativePath,
                    isBinary = item.isBinary,
                    local = item.local.toModel(),
                    incoming = item.incoming.toModel(),
                    state = SyncReviewItemState.valueOf(item.state),
                    message = item.message,
                )
            },
        timestamp = timestamp,
        kind = SyncReviewSessionKind.valueOf(reviewKind),
        workspaceGeneration = workspaceGeneration,
        validationStatus = PendingSyncValidationStatus.valueOf(payload.validationStatus),
    )
}

@Serializable
private data class PendingSyncReviewPayload(
    val schemaVersion: Int = 2,
    val validationStatus: String = PendingSyncValidationStatus.PENDING_RELOAD.name,
    val items: List<PendingSyncReviewItemPayload>,
)

@Serializable
private data class PendingSyncReviewItemPayload(
    val relativePath: String,
    val isBinary: Boolean,
    val local: PendingSyncReviewSideMetadataPayload,
    val incoming: PendingSyncReviewSideMetadataPayload,
    val state: String,
    val message: String? = null,
)

@Serializable
private data class PendingSyncReviewSideMetadataPayload(
    val locator: String,
    val contentHash: String? = null,
    val lastModified: Long? = null,
    val size: Long? = null,
    val etag: String? = null,
)

private fun PendingSyncReviewSideMetadataPayload.toModel(): PendingSyncSideMetadata =
    PendingSyncSideMetadata(
        locator = locator,
        contentHash = contentHash,
        lastModified = lastModified,
        size = size,
        etag = etag,
    )

private fun PendingSyncSideMetadata.toReviewPayload(): PendingSyncReviewSideMetadataPayload =
    PendingSyncReviewSideMetadataPayload(
        locator = locator,
        contentHash = contentHash,
        lastModified = lastModified,
        size = size,
        etag = etag,
    )

private fun String.pendingContentHash(): String =
    toByteArray(Charsets.UTF_8).md5Hex()
