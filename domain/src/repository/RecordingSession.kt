package com.lomo.domain.repository

import com.lomo.domain.model.RecordingSessionState
import kotlinx.coroutines.flow.StateFlow

interface RecordingSession {
    val state: StateFlow<RecordingSessionState>

    val durationMillis: StateFlow<Long>

    val amplitude: StateFlow<Int>

    val errorMessage: StateFlow<String?>

    suspend fun startRecording()

    suspend fun stopRecording(): String?

    suspend fun cancelRecording()

    fun clearError()
}
