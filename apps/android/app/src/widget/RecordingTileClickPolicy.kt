package com.lomo.app.widget

import com.lomo.domain.model.RecordingSessionState

sealed interface TileClickAction {
    data object LaunchStartRecording : TileClickAction

    /** The stop intent is bound to the displayed capture so a stale tap can never stop another session. */
    data class LaunchStopRecording(
        val captureId: String,
    ) : TileClickAction
}

enum class RecordingTilePresentation {
    Start,
    Stop,
}

class RecordingTileClickPolicy {
    fun decide(state: RecordingSessionState): TileClickAction =
        when (state) {
            RecordingSessionState.Idle -> TileClickAction.LaunchStartRecording
            is RecordingSessionState.Recording -> TileClickAction.LaunchStopRecording(state.draftId.value)
        }

    fun presentation(state: RecordingSessionState): RecordingTilePresentation =
        when (state) {
            RecordingSessionState.Idle -> RecordingTilePresentation.Start
            is RecordingSessionState.Recording -> RecordingTilePresentation.Stop
        }
}
