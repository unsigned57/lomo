package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.source.readStorageRootConfig
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceRootTransition
import com.lomo.domain.repository.DirectorySettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DirectorySettingsRepositoryImpl(
    private val dataSource: WorkspaceConfigSource,
    private val dataStore: LomoDataStore,
) : DirectorySettingsRepository {
    override fun observeLocation(area: StorageArea): Flow<StorageLocation?> = dataSource
        .getRootFlow(area.toStorageRootType())
        .map { raw -> raw?.let(::StorageLocation) }

    override suspend fun currentLocation(area: StorageArea): StorageLocation? =
        dataStore.readStorageRootConfig(area.toStorageRootType()).preferredLocation()?.let(::StorageLocation)

    override fun observeDisplayName(area: StorageArea): Flow<String?> =
        dataSource.getRootDisplayNameFlow(area.toStorageRootType())

    override suspend fun applyLocation(update: StorageAreaUpdate) {
        val location = update.location
        if (location == null) {
            when (update.area) {
                StorageArea.IMAGE -> dataStore.updateImageUri(null)
                StorageArea.VOICE -> dataStore.updateVoiceUri(null)
                StorageArea.ROOT, StorageArea.SYNC_INBOX -> error("Only media locations can be reset")
            }
        } else {
            dataSource.setRoot(type = update.area.toStorageRootType(), pathOrUri = location.raw)
        }
    }

    override suspend fun prepareRootTransition(
        candidate: StorageLocation,
        mediaChange: com.lomo.domain.model.MediaDirectoryChange?,
    ): WorkspaceRootTransition = dataStore.prepareRootTransition(currentRootLocation(), candidate, mediaChange)

    override suspend fun markRootTransitionActivated(transitionId: String): WorkspaceRootTransition =
        dataStore.markRootTransitionActivated(transitionId)

    override suspend fun commitRootTransition(transitionId: String) {
        dataStore.commitRootTransition(transitionId)
    }

    override suspend fun rollbackRootTransition(transitionId: String) {
        dataStore.rollbackRootTransition(transitionId)
    }

    override suspend fun pendingRootTransition(): WorkspaceRootTransition? = dataStore.pendingRootTransition()

    override suspend fun recoverRootLocation(): StorageLocation? = dataStore.recoverRootLocation()
}

internal fun StorageArea.toStorageRootType(): StorageRootType = when (this) {
    StorageArea.ROOT -> StorageRootType.MAIN
    StorageArea.IMAGE -> StorageRootType.IMAGE
    StorageArea.VOICE -> StorageRootType.VOICE
    StorageArea.SYNC_INBOX -> StorageRootType.SYNC_INBOX
}
