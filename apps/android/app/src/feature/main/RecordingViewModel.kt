package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.usecase.RecordingSessionUseCase

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


// behavior-contract: session-facade-ok: wraps RecordingSessionUseCase platform session; no screen state machine
class RecordingViewModel(
    private val recordingSessionUseCase: RecordingSessionUseCase,
) : ViewModel() {
        val isRecording: StateFlow<Boolean> =
            recordingSessionUseCase.isRecording
                .stateIn(viewModelScope, appWhileSubscribed(), false)

        val recordingDuration: StateFlow<Long> = recordingSessionUseCase.durationMillis

        val recordingAmplitude: StateFlow<Int?> = recordingSessionUseCase.amplitude

        /** Identity of the live capture; null while idle. External stop commands are bound to it. */
        val recordingCaptureId: StateFlow<String?> =
            recordingSessionUseCase.state
                .map { state ->
                    (state as? com.lomo.domain.model.RecordingSessionState.Recording)?.run { draftId.value }
                }
                .stateIn(viewModelScope, appWhileSubscribed(), null)

        val errorMessage: StateFlow<String?> = recordingSessionUseCase.errorMessage

        fun startRecording() {
            viewModelScope.launch { recordingSessionUseCase.startRecording() }
        }

        fun stopRecording(onResult: (String?) -> Unit) {
            viewModelScope.launch {
                onResult(recordingSessionUseCase.stopRecording()?.takeIf(String::isNotBlank))
            }
        }

        fun cancelRecording() {
            viewModelScope.launch { recordingSessionUseCase.cancelRecording() }
        }

        fun clearError() {
            recordingSessionUseCase.clearError()
        }
    }
