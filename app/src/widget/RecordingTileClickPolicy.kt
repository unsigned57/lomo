package com.lomo.app.widget

import com.lomo.domain.model.RecordingSessionState

sealed interface TileClickAction {
    data object LaunchStartRecording : TileClickAction

    data object LaunchStopRecording : TileClickAction
}

enum class RecordingTilePresentation {
    Start,
    Stop,
}

class RecordingTileClickPolicy {
    fun decide(state: RecordingSessionState): TileClickAction =
        when (state) {
            RecordingSessionState.Idle -> TileClickAction.LaunchStartRecording
            is RecordingSessionState.Recording -> TileClickAction.LaunchStopRecording
        }

    fun presentation(state: RecordingSessionState): RecordingTilePresentation =
        when (state) {
            RecordingSessionState.Idle -> RecordingTilePresentation.Start
            is RecordingSessionState.Recording -> RecordingTilePresentation.Stop
        }
}
