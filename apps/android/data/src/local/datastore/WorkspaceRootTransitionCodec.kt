package com.lomo.data.local.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.lomo.domain.model.MediaDirectoryChange
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceRootTransition
import com.lomo.domain.model.WorkspaceRootTransitionCorruptionException
import java.util.UUID

internal fun Preferences.readRootTransition(): WorkspaceRootTransition? {
    val id = this[LomoDataStoreKeys.ROOT_TRANSITION_ID]
    val hasAny =
        contains(LomoDataStoreKeys.ROOT_TRANSITION_ID) ||
            contains(LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS) ||
            contains(LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS_PRESENT) ||
            contains(LomoDataStoreKeys.ROOT_TRANSITION_CANDIDATE) ||
            contains(LomoDataStoreKeys.ROOT_TRANSITION_PHASE) || hasMediaTransitionFields()
    if (id == null) {
        requireTransitionCondition(!hasAny, "Workspace transition journal is incomplete")
        return null
    }
    val previousPresent = requireTransitionValue(
        this[LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS_PRESENT],
        "Workspace transition previous marker is missing",
    )
    val previousRaw = this[LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS]
    val previous =
        if (previousPresent) {
            requireTransitionString(
                previousRaw,
                "Workspace transition previous root is missing",
            ).let(::StorageLocation)
        } else {
            requireTransitionCondition(
                previousRaw == null,
                "Workspace transition previous root contradicts its marker",
            )
            null
        }
    val candidate = requireTransitionString(
        this[LomoDataStoreKeys.ROOT_TRANSITION_CANDIDATE],
        "Workspace transition candidate is missing",
    ).let(::StorageLocation)
    val phaseRaw = requireTransitionString(
        this[LomoDataStoreKeys.ROOT_TRANSITION_PHASE],
        "Workspace transition phase is missing",
    )
    val phase = requireTransitionPhase(phaseRaw)
    return try {
        UUID.fromString(id)
        WorkspaceRootTransition(id, previous, candidate, phase, readMediaChange())
    } catch (error: IllegalArgumentException) {
        throw transitionCorruption("Workspace transition journal contains invalid values", error)
    }
}

private fun Preferences.hasMediaTransitionFields(): Boolean = contains(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_AREA) ||
    contains(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS) ||
    contains(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS_PRESENT) ||
    contains(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_CANDIDATE) ||
    contains(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_FOLLOWS_NOTES)

private fun Preferences.readMediaChange(): MediaDirectoryChange? {
    val areaRaw = this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_AREA]
    if (areaRaw == null) {
        requireTransitionCondition(!hasMediaTransitionFields(), "Media transition journal is incomplete")
        return null
    }
    val present = requireTransitionValue(
        this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS_PRESENT],
        "Media previous marker is missing",
    )
    val previous = this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS]
    val previousLocation = if (present) {
        StorageLocation(requireTransitionString(previous, "Previous media directory is missing"))
    } else {
        requireTransitionCondition(previous == null, "Media previous value contradicts its marker")
        null
    }
    val candidate = this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_CANDIDATE]
    val followsNotes = this[LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_FOLLOWS_NOTES]
    val location = when (followsNotes) {
        true -> {
            requireTransitionCondition(candidate == null, "Media follow mode contradicts its candidate")
            null
        }

        null -> StorageLocation(requireTransitionString(candidate, "Media candidate is missing"))

        false -> throw transitionCorruption("Media follow marker is invalid")
    }
    return MediaDirectoryChange(StorageArea.valueOf(areaRaw), previousLocation, location)
}

internal fun MutablePreferences.clearRootTransition() {
    remove(LomoDataStoreKeys.ROOT_TRANSITION_ID)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_PREVIOUS_PRESENT)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_CANDIDATE)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_PHASE)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_AREA)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_PREVIOUS_PRESENT)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_CANDIDATE)
    remove(LomoDataStoreKeys.ROOT_TRANSITION_MEDIA_FOLLOWS_NOTES)
}

internal fun transitionCorruption(
    message: String,
    cause: Throwable? = null,
): WorkspaceRootTransitionCorruptionException = WorkspaceRootTransitionCorruptionException(
    if (cause == null) message else "$message: ${cause.message ?: cause.javaClass.simpleName}",
)
