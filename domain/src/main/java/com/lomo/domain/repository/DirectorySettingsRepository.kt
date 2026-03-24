package com.lomo.domain.repository

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.Flow

interface DirectoryLocationRepository {
    fun observeLocation(area: StorageArea): Flow<StorageLocation?>

    suspend fun currentLocation(area: StorageArea): StorageLocation?

    suspend fun applyLocation(update: StorageAreaUpdate)
}

interface DirectoryDisplayNameRepository {
    fun observeDisplayName(area: StorageArea): Flow<String?>
}

interface RootDirectorySettingsRepository :
    DirectoryLocationRepository,
    DirectoryDisplayNameRepository {
    fun observeRootLocation(): Flow<StorageLocation?> = observeLocation(StorageArea.ROOT)

    suspend fun currentRootLocation(): StorageLocation? = currentLocation(StorageArea.ROOT)

    fun observeRootDisplayName(): Flow<String?> = observeDisplayName(StorageArea.ROOT)

    suspend fun applyRootLocation(
        location: StorageLocation,
    ) = applyLocation(StorageAreaUpdate(StorageArea.ROOT, location))
}

interface MediaDirectorySettingsRepository :
    DirectoryLocationRepository,
    DirectoryDisplayNameRepository {
    fun observeImageLocation(): Flow<StorageLocation?> = observeLocation(StorageArea.IMAGE)

    fun observeImageDisplayName(): Flow<String?> = observeDisplayName(StorageArea.IMAGE)

    fun observeVoiceLocation(): Flow<StorageLocation?> = observeLocation(StorageArea.VOICE)

    fun observeVoiceDisplayName(): Flow<String?> = observeDisplayName(StorageArea.VOICE)
}

interface DirectorySettingsRepository :
    DirectoryLocationRepository,
    DirectoryDisplayNameRepository,
    RootDirectorySettingsRepository,
    MediaDirectorySettingsRepository
