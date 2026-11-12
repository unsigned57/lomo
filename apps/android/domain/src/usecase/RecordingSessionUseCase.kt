package com.lomo.domain.usecase

import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.repository.RecordingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class RecordingSessionUseCase(private val recordingSession: RecordingSession) {
    val state: StateFlow<RecordingSessionState> = recordingSession.state

    val isRecording: Flow<Boolean> = state.map { recordingState ->
        recordingState is RecordingSessionState.Recording
    }

    val durationMillis: StateFlow<Long> = recordingSession.durationMillis

    val amplitude: StateFlow<Int?> = recordingSession.amplitude

    val errorMessage: StateFlow<String?> = recordingSession.errorMessage

    suspend fun startRecording(draftId: com.lomo.domain.model.DraftId) {
        recordingSession.startRecording(draftId)
    }

    suspend fun stopRecording(): String? = recordingSession.stopRecording()

    suspend fun cancelRecording() {
        recordingSession.cancelRecording()
    }

    fun clearError() {
        recordingSession.clearError()
    }
}
