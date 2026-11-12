package com.lomo.domain.model

sealed interface RecordingSessionState {
    data object Idle : RecordingSessionState

    data class Recording(
        val filename: String,
        val startedAtMillis: Long,
        val captureId: RecordingCaptureId,
        /** The receiving editor's draft identity; several distinct captures may share this lease owner. */
        val draftId: DraftId,
    ) : RecordingSessionState
}
