package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.usecase.RecordingSessionUseCase

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class RecordingViewModel(
    private val recordingSessionUseCase: RecordingSessionUseCase,
) : ViewModel() {
        val isRecording: StateFlow<Boolean> =
            recordingSessionUseCase.isRecording
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        val recordingDuration: StateFlow<Long> = recordingSessionUseCase.durationMillis

        val recordingAmplitude: StateFlow<Int> = recordingSessionUseCase.amplitude

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
