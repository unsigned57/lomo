package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.dao.RawS3SyncMetadataDao
import com.lomo.data.local.dao.RawWebDavSyncMetadataDao
import com.lomo.data.local.dao.WebDavSyncMetadataDao
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.local.entity.WebDavSyncMetadataEntity
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomBackedWebDavSyncMetadataStore
    @Inject
    constructor(
        private val dao: RawWebDavSyncMetadataDao,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : WebDavSyncMetadataDao {
        override suspend fun getAll(): List<WebDavSyncMetadataEntity> =
            dao.getAll(activeGeneration())

        override suspend fun getByRelativePaths(relativePaths: List<String>): List<WebDavSyncMetadataEntity> =
            if (relativePaths.isEmpty()) {
                emptyList()
            } else {
                dao.getByRelativePaths(relativePaths = relativePaths, workspaceGeneration = activeGeneration())
            }

        override suspend fun upsertAll(entities: List<WebDavSyncMetadataEntity>) {
            if (entities.isEmpty()) return
            val generation = activeGeneration()
            dao.upsertAll(entities.map { entity -> entity.copy(workspaceGeneration = generation) })
        }

        override suspend fun deleteByRelativePath(relativePath: String) {
            dao.deleteByRelativePath(relativePath = relativePath, workspaceGeneration = activeGeneration())
        }

        override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
            if (relativePaths.isEmpty()) return
            dao.deleteByRelativePaths(relativePaths = relativePaths, workspaceGeneration = activeGeneration())
        }

        override suspend fun clearAll() {
            dao.clearAll(activeGeneration())
        }

        override suspend fun replaceAll(entities: List<WebDavSyncMetadataEntity>) {
            val generation = activeGeneration()
            dao.replaceAll(
                entities = entities.map { entity -> entity.copy(workspaceGeneration = generation) },
                workspaceGeneration = generation,
            )
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }

@Singleton
class RoomBackedS3SyncMetadataStore
    @Inject
    constructor(
        private val dao: RawS3SyncMetadataDao,
        private val generationProvider: WorkspaceSyncGenerationProvider,
    ) : S3SyncMetadataDao {
        override suspend fun getAll(): List<S3SyncMetadataEntity> =
            dao.getAll(activeGeneration())

        override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> =
            dao.getAllPlannerMetadataSnapshots(activeGeneration())

        override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> =
            dao.getAllRemoteMetadataSnapshots(activeGeneration())

        override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
            if (relativePaths.isEmpty()) {
                emptyList()
            } else {
                dao.getByRelativePaths(relativePaths = relativePaths, workspaceGeneration = activeGeneration())
            }

        override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
            if (entities.isEmpty()) return
            val generation = activeGeneration()
            dao.upsertAll(entities.map { entity -> entity.copy(workspaceGeneration = generation) })
        }

        override suspend fun deleteByRelativePath(relativePath: String) {
            dao.deleteByRelativePath(relativePath = relativePath, workspaceGeneration = activeGeneration())
        }

        override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
            if (relativePaths.isEmpty()) return
            dao.deleteByRelativePaths(relativePaths = relativePaths, workspaceGeneration = activeGeneration())
        }

        override suspend fun clearAll() {
            dao.clearAll(activeGeneration())
        }

        override suspend fun replaceAll(entities: List<S3SyncMetadataEntity>) {
            val generation = activeGeneration()
            dao.replaceAll(
                entities = entities.map { entity -> entity.copy(workspaceGeneration = generation) },
                workspaceGeneration = generation,
            )
        }

        private suspend fun activeGeneration(): String = generationProvider.activeGeneration().value
    }
