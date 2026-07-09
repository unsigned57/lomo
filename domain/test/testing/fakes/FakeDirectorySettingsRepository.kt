package com.lomo.domain.testing.fakes

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDirectorySettingsRepository(
    private val eventLog: MutableList<String>? = null,
) : DirectorySettingsRepository {
    private val locations =
        MutableStateFlow<MutableMap<StorageArea, StorageLocation?>>(mutableMapOf())
    private val displayNames =
        MutableStateFlow<MutableMap<StorageArea, String?>>(mutableMapOf())

    val appliedUpdates = mutableListOf<StorageAreaUpdate>()
    var applyFailure: Exception? = null

    fun setLocation(
        area: StorageArea,
        location: StorageLocation?,
    ) {
        locations.value = locations.value.toMutableMap().also { values -> values[area] = location }
    }

    fun setDisplayName(
        area: StorageArea,
        displayName: String?,
    ) {
        displayNames.value = displayNames.value.toMutableMap().also { values -> values[area] = displayName }
    }

    override fun observeLocation(area: StorageArea): Flow<StorageLocation?> =
        locations.map { values -> values[area] }

    override suspend fun currentLocation(area: StorageArea): StorageLocation? = locations.value[area]

    override suspend fun applyLocation(update: StorageAreaUpdate) {
        applyFailure?.let { throw it }
        eventLog?.add("directory.applyLocation:${update.area}")
        appliedUpdates += update
        setLocation(area = update.area, location = update.location)
    }

    override fun observeDisplayName(area: StorageArea): Flow<String?> =
        displayNames.map { values -> values[area] }
}
