package com.lomo.data.repository
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.model.WorkspaceRootTransition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
class DirectorySettingsRepositoryImpl
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

        override suspend fun prepareRootTransition(candidate: StorageLocation): WorkspaceRootTransition =
            dataStore.prepareRootTransition(currentRootLocation(), candidate)

        override suspend fun markRootTransitionActivated(transitionId: String): WorkspaceRootTransition =
            dataStore.markRootTransitionActivated(transitionId)

        override suspend fun commitRootTransition(transitionId: String) {
            dataStore.commitRootTransition(transitionId)
        }

        override suspend fun rollbackRootTransition(transitionId: String) {
            dataStore.rollbackRootTransition(transitionId)
        }

        override suspend fun pendingRootTransition(): WorkspaceRootTransition? =
            dataStore.pendingRootTransition()

        override suspend fun recoverRootLocation(): StorageLocation? =
            dataStore.recoverRootLocation()
    }
internal fun StorageArea.toStorageRootType(): StorageRootType =
    when (this) {
        StorageArea.ROOT -> StorageRootType.MAIN
        StorageArea.IMAGE -> StorageRootType.IMAGE
        StorageArea.VOICE -> StorageRootType.VOICE
        StorageArea.SYNC_INBOX -> StorageRootType.SYNC_INBOX
    }
