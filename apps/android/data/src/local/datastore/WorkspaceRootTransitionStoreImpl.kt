package com.lomo.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.lomo.domain.model.MediaDirectoryChange
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceRootTransition
import com.lomo.domain.model.WorkspaceRootTransitionPhase
import kotlinx.coroutines.flow.first
import java.util.UUID

internal class WorkspaceRootTransitionStoreImpl(private val dataStore: DataStore<Preferences>) :
    LomoWorkspaceRootTransitionStore {
    override suspend fun prepareRootTransition(
        previous: StorageLocation?,
        candidate: StorageLocation,
        mediaChange: MediaDirectoryChange?,
    ): WorkspaceRootTransition {
        val transition =
            WorkspaceRootTransition(
                id = newTransitionId(),
                previous = previous,
                candidate = candidate,
                phase = WorkspaceRootTransitionPhase.PREPARED,
                mediaChange = mediaChange,
            )
        dataStore.editPreferences {
            requireNoPendingTransition(this)
            requireCommittedRoot(this, previous)
            requireCommittedMedia(this, mediaChange)
            this[LomoDataStoreKeys.ROOT_TRANSITION_ID] = transition.id
            this[LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS_PRESENT] = previous != null
            setOrRemove(LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS, previous?.raw)
            this[LomoDataStoreKeys.ROOT_TRANSITION_CANDIDATE] = candidate.raw
            this[LomoDataStoreKeys.ROOT_TRANSITION_PHASE] = transition.phase.name
            mediaChange?.let { change ->
                this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_AREA] = change.area.name
                this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS_PRESENT] = change.previous != null
                setOrRemove(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS, change.previous?.raw)
                this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_CANDIDATE] = change.candidate.raw
            }
        }
        return transition
    }

    // The transition store owns transition identity; ids are minted here, never by callers.
    private fun newTransitionId(): String = UUID.randomUUID().toString()

    override suspend fun markRootTransitionActivated(transitionId: String): WorkspaceRootTransition {
        var activated: WorkspaceRootTransition? = null
        dataStore.editPreferences {
            val current = requireTransition(transitionId)
            if (current.phase != WorkspaceRootTransitionPhase.PREPARED) {
                throw transitionCorruption("Workspace transition is not prepared")
            }
            requireCommittedRoot(this, current.previous)
            requireCommittedMedia(this, current.mediaChange)
            activated = current.copy(phase = WorkspaceRootTransitionPhase.ACTIVATED)
            this[LomoDataStoreKeys.ROOT_TRANSITION_PHASE] = WorkspaceRootTransitionPhase.ACTIVATED.name
        }
        return checkNotNull(activated)
    }

    override suspend fun commitRootTransition(transitionId: String) {
        dataStore.editPreferences {
            val current = requireTransition(transitionId)
            if (current.phase != WorkspaceRootTransitionPhase.ACTIVATED) {
                throw transitionCorruption("Workspace transition is not activated")
            }
            requireCommittedRoot(this, current.previous)
            requireCommittedMedia(this, current.mediaChange)
            putCommittedRoot(current.candidate)
            current.mediaChange?.let { this[mediaLocationKey(it.area)] = it.candidate.raw }
            clearRootTransition()
        }
    }

    override suspend fun rollbackRootTransition(transitionId: String) {
        dataStore.editPreferences {
            val current = requireTransition(transitionId)
            requireCommittedRoot(this, current.previous)
            requireCommittedMedia(this, current.mediaChange)
            clearRootTransition()
        }
    }

    override suspend fun pendingRootTransition(): WorkspaceRootTransition? = dataStore.data.first().readRootTransition()

    override suspend fun recoverRootLocation(): StorageLocation? {
        var recovered: StorageLocation? = null
        dataStore.editPreferences {
            val transition = readRootTransition()
            val committed = committedRoot()
            if (transition != null && committed != transition.previous) {
                throw transitionCorruption("Pending transition previous root differs from committed root")
            }
            requireCommittedMedia(this, transition?.mediaChange)
            clearRootTransition()
            recovered = committed
        }
        return recovered
    }
}

private fun MutablePreferences.putCommittedRoot(location: StorageLocation) {
    this[LomoDataStoreKeys.ROOT_LOCATION] = location.raw
}

private fun Preferences.committedRoot(): StorageLocation? =
    this[LomoDataStoreKeys.ROOT_LOCATION]?.let(::StorageLocation)

private fun requireCommittedRoot(preferences: Preferences, expected: StorageLocation?) {
    if (preferences.committedRoot() != expected) {
        throw transitionCorruption("Committed root changed during workspace transition")
    }
}

private fun mediaLocationKey(area: StorageArea): Preferences.Key<String> = when (area) {
    StorageArea.IMAGE -> LomoDataStoreKeys.IMAGE_LOCATION
    StorageArea.VOICE -> LomoDataStoreKeys.VOICE_LOCATION
    StorageArea.ROOT, StorageArea.SYNC_INBOX -> throw transitionCorruption("Invalid attachment transition area")
}

private fun requireCommittedMedia(preferences: Preferences, change: MediaDirectoryChange?) {
    if (change != null && preferences[mediaLocationKey(change.area)] != change.previous?.raw) {
        throw transitionCorruption("Committed media selection changed during workspace transition")
    }
}

private fun Preferences.requireTransition(transitionId: String): WorkspaceRootTransition {
    val transition = readRootTransition()
        ?: throw transitionCorruption("Workspace transition does not exist")
    if (transition.id != transitionId) {
        throw transitionCorruption("Workspace transition id does not match")
    }
    return transition
}

private fun requireNoPendingTransition(preferences: Preferences) {
    if (preferences.readRootTransition() != null) {
        throw transitionCorruption("A workspace transition is already pending")
    }
}
