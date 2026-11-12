package com.lomo.domain.model

/** A provider inventory is usable only after a complete observation of the selected directories. */
sealed interface ImageLocationSnapshot {
    data object Loading : ImageLocationSnapshot

    data class Ready(val entries: Map<MediaEntryId, MediaImageDescriptor>) : ImageLocationSnapshot

    /** The selection remains valid, but its display inventory could not be observed. */
    data class Unavailable(val code: String, val diagnostic: String) : ImageLocationSnapshot
}
