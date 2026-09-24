package com.lomo.app.testing.fakes

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceRootTransition
import com.lomo.domain.repository.DirectorySettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDirectorySettingsRepository : DirectorySettingsRepository {
    private val locations =
        MutableStateFlow<MutableMap<StorageArea, StorageLocation?>>(mutableMapOf())
    private val displayNames =
        MutableStateFlow<MutableMap<StorageArea, String?>>(mutableMapOf())

    fun setLocation(
        area: StorageArea,
        location: StorageLocation?,
    ) {
        locations.value = locations.value.toMutableMap().also { values -> values[area] = location }
    }

    override fun observeLocation(area: StorageArea): Flow<StorageLocation?> =
        locations.map { values -> values[area] }

    override suspend fun currentLocation(area: StorageArea): StorageLocation? = locations.value[area]

    override suspend fun applyLocation(update: StorageAreaUpdate) {
        setLocation(area = update.area, location = update.location)
    }

    override fun observeDisplayName(area: StorageArea): Flow<String?> =
        displayNames.map { values -> values[area] }

    override suspend fun prepareRootTransition(candidate: StorageLocation): WorkspaceRootTransition =
        error("transitions not used by this test")

    override suspend fun markRootTransitionActivated(transitionId: String): WorkspaceRootTransition =
        error("transitions not used by this test")

    override suspend fun commitRootTransition(transitionId: String) =
        error("transitions not used by this test")

    override suspend fun rollbackRootTransition(transitionId: String) =
        error("transitions not used by this test")

    override suspend fun pendingRootTransition(): WorkspaceRootTransition? = null

    override suspend fun recoverRootLocation(): StorageLocation? = null
}
