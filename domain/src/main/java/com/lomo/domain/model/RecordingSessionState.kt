package com.lomo.domain.model

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState

    data class Recording(
        val filename: String,
        val startedAtMillis: Long,
    ) : RecordingSessionState
}
