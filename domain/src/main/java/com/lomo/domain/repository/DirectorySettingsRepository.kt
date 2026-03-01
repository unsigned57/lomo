package com.lomo.domain.repository

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import kotlinx.coroutines.flow.Flow

interface DirectorySettingsRepository {
    fun observeLocation(area: StorageArea): Flow<StorageLocation?>

    suspend fun currentLocation(area: StorageArea): StorageLocation?

    fun observeDisplayName(area: StorageArea): Flow<String?>

    suspend fun applyLocation(update: StorageAreaUpdate)

    fun observeRootLocation(): Flow<StorageLocation?> = observeLocation(StorageArea.ROOT)

    suspend fun currentRootLocation(): StorageLocation? = currentLocation(StorageArea.ROOT)

    fun observeRootDisplayName(): Flow<String?> = observeDisplayName(StorageArea.ROOT)

    fun observeImageLocation(): Flow<StorageLocation?> = observeLocation(StorageArea.IMAGE)

    fun observeImageDisplayName(): Flow<String?> = observeDisplayName(StorageArea.IMAGE)

    fun observeVoiceLocation(): Flow<StorageLocation?> = observeLocation(StorageArea.VOICE)

    fun observeVoiceDisplayName(): Flow<String?> = observeDisplayName(StorageArea.VOICE)

    suspend fun applyRootLocation(location: StorageLocation) = applyLocation(StorageAreaUpdate(StorageArea.ROOT, location))
}
