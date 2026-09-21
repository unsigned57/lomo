package com.lomo.domain.model

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState

    data class Recording(
        val filename: String,
        val startedAtMillis: Long,
        /** The recording session's draft identity; its media lease transfers on the created memo. */
        val draftId: DraftId,
    ) : RecordingSessionState
}
