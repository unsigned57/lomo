package com.lomo.data.testing.fakes

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.DirectorySettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDirectorySettingsRepository : DirectorySettingsRepository {
    private val locations =
        MutableStateFlow<MutableMap<StorageArea, StorageLocation?>>(mutableMapOf())
    private val displayNames =
        MutableStateFlow<MutableMap<StorageArea, String?>>(mutableMapOf())
    private var pendingTransition: com.lomo.domain.model.WorkspaceRootTransition? = null
    private var nextTransitionId: Int = 1
    var recoveredRootOverride: StorageLocation? = null

    fun setLocation(area: StorageArea, location: StorageLocation?) {
        locations.value = locations.value.toMutableMap().also { values -> values[area] = location }
    }

    override fun observeLocation(area: StorageArea): Flow<StorageLocation?> = locations.map { values -> values[area] }

    override suspend fun currentLocation(area: StorageArea): StorageLocation? = locations.value[area]

    override suspend fun applyLocation(update: StorageAreaUpdate) {
        setLocation(area = update.area, location = update.location)
    }

    override fun observeDisplayName(area: StorageArea): Flow<String?> = displayNames.map { values -> values[area] }

    override suspend fun prepareRootTransition(
        candidate: StorageLocation,
        mediaChange: com.lomo.domain.model.MediaDirectoryChange?,
    ): com.lomo.domain.model.WorkspaceRootTransition {
        check(pendingTransition == null) { "Workspace transition already pending" }
        return com.lomo.domain.model.WorkspaceRootTransition(
            id = "test-transition-${nextTransitionId++}",
            previous = currentRootLocation(),
            candidate = candidate,
            phase = com.lomo.domain.model.WorkspaceRootTransitionPhase.PREPARED,
            mediaChange = mediaChange,
        ).also { pendingTransition = it }
    }

    override suspend fun markRootTransitionActivated(
        transitionId: String,
    ): com.lomo.domain.model.WorkspaceRootTransition {
        val current = requirePendingTransition(transitionId)
        check(current.phase == com.lomo.domain.model.WorkspaceRootTransitionPhase.PREPARED)
        return current.copy(phase = com.lomo.domain.model.WorkspaceRootTransitionPhase.ACTIVATED)
            .also { pendingTransition = it }
    }

    override suspend fun commitRootTransition(transitionId: String) {
        val current = requirePendingTransition(transitionId)
        check(current.phase == com.lomo.domain.model.WorkspaceRootTransitionPhase.ACTIVATED)
        setLocation(StorageArea.ROOT, current.candidate)
        current.mediaChange?.let { setLocation(it.area, it.candidate) }
        pendingTransition = null
    }

    override suspend fun rollbackRootTransition(transitionId: String) {
        requirePendingTransition(transitionId)
        pendingTransition = null
    }

    override suspend fun pendingRootTransition(): com.lomo.domain.model.WorkspaceRootTransition? = pendingTransition

    override suspend fun recoverRootLocation(): StorageLocation? {
        pendingTransition = null
        return recoveredRootOverride ?: currentRootLocation()
    }

    private fun requirePendingTransition(transitionId: String): com.lomo.domain.model.WorkspaceRootTransition {
        val current = checkNotNull(pendingTransition) { "Workspace transition is missing" }
        check(current.id == transitionId) { "Workspace transition id mismatch" }
        return current
    }
}
