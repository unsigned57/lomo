package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectorySettingsRepositoryImpl
    @Inject
    constructor(
        private val dataSource: WorkspaceConfigSource,
        private val dataStore: LomoDataStore,
    ) : DirectorySettingsRepository {
        override fun observeLocation(area: StorageArea): Flow<StorageLocation?> =
            dataSource
                .getRootFlow(area.toStorageRootType())
                .map { raw -> raw?.let(::StorageLocation) }

        override suspend fun currentLocation(area: StorageArea): StorageLocation? =
            when (area) {
                StorageArea.ROOT -> dataStore.rootUri.first() ?: dataStore.rootDirectory.first()
                StorageArea.IMAGE -> dataStore.imageUri.first() ?: dataStore.imageDirectory.first()
                StorageArea.VOICE -> dataStore.voiceUri.first() ?: dataStore.voiceDirectory.first()
                StorageArea.SYNC_INBOX -> dataStore.syncInboxUri.first() ?: dataStore.syncInboxDirectory.first()
            }?.let(::StorageLocation)

        override fun observeDisplayName(area: StorageArea): Flow<String?> =
            dataSource.getRootDisplayNameFlow(area.toStorageRootType())

        override suspend fun applyLocation(update: StorageAreaUpdate) {
            dataSource.setRoot(
                type = update.area.toStorageRootType(),
                pathOrUri = update.location.raw,
            )
        }
    }

internal fun StorageArea.toStorageRootType(): StorageRootType =
    when (this) {
        StorageArea.ROOT -> StorageRootType.MAIN
        StorageArea.IMAGE -> StorageRootType.IMAGE
        StorageArea.VOICE -> StorageRootType.VOICE
        StorageArea.SYNC_INBOX -> StorageRootType.SYNC_INBOX
    }
