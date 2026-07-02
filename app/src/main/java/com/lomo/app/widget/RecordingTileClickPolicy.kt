package com.lomo.app.widget

import com.lomo.domain.model.RecordingSessionState

data class RecordingPreconditions(
    val hasRecordAudioPermission: Boolean,
    val isVoiceWorkspaceReady: Boolean,
    val isAppLockSatisfied: Boolean,
) {
    val areSatisfied: Boolean
        get() = hasRecordAudioPermission && isVoiceWorkspaceReady && isAppLockSatisfied
}

sealed interface TileClickAction {
    data object StartRecording : TileClickAction

    data object StopRecording : TileClickAction

    data object LaunchMainActivityWithStartRecording : TileClickAction
}

class RecordingTileClickPolicy {
    fun decide(
        preconditions: RecordingPreconditions,
        state: RecordingSessionState,
    ): TileClickAction {
        if (!preconditions.areSatisfied) {
            return TileClickAction.LaunchMainActivityWithStartRecording
        }

        return when (state) {
            RecordingSessionState.Idle -> TileClickAction.StartRecording
            is RecordingSessionState.Recording -> TileClickAction.StopRecording
        }
    }
}
