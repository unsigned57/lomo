package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Manages voice recording state and lifecycle, extracted from MainViewModel
 * to satisfy the Single Responsibility Principle.
 *
 * Responsibilities:
 * - Recording state (isRecording, duration, amplitude)
 * - Start / stop / cancel recording
 * - Creating and cleaning up voice files via the repository
 */
@HiltViewModel
class RecordingViewModel
    @Inject
    constructor(
        private val recordingCoordinator: RecordingCoordinator,
    ) : ViewModel() {
        companion object {
            private val VOICE_FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        }

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _recordingDuration = MutableStateFlow(0L)
        val recordingDuration: StateFlow<Long> = _recordingDuration

        private val _recordingAmplitude = MutableStateFlow(0)
        val recordingAmplitude: StateFlow<Int> = _recordingAmplitude

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        val voiceDirectory: StateFlow<String?> =
            recordingCoordinator
                .voiceDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private var recordingJob: kotlinx.coroutines.Job? = null
        private var currentRecordingTarget: String? = null
        private var currentRecordingFilename: String? = null

        fun startRecording() {
            viewModelScope.launch {
                try {
                    val timestamp = VOICE_FILE_TIMESTAMP_FORMATTER.format(LocalDateTime.now())
                    val filename = "voice_$timestamp.m4a"
                    val target = recordingCoordinator.startRecording(filename)
                    currentRecordingTarget = target
                    currentRecordingFilename = filename
                    _isRecording.value = true
                    _recordingDuration.value = 0

                    startRecordingTimer()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start recording")
                    _errorMessage.value = "Failed to start recording: ${e.message}"
                    cancelRecording()
                }
            }
        }

        private fun startRecordingTimer() {
            recordingJob?.cancel()
            recordingJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()
                    while (isActive) {
                        _recordingDuration.value = System.currentTimeMillis() - startTime
                        _recordingAmplitude.value = recordingCoordinator.currentAmplitude()
                        kotlinx.coroutines.delay(50) // Update every 50ms for smooth visualizer
                    }
                }
        }

        fun stopRecording(onResult: (String) -> Unit) {
            if (!_isRecording.value) return

            val filename = currentRecordingFilename
            resetRecordingState()
            viewModelScope.launch {
                try {
                    recordingCoordinator.stopRecording()
                    if (!filename.isNullOrBlank()) {
                        // Use just the filename - voice directory is resolved by AudioPlayerManager
                        val markdown = "![voice]($filename)"
                        onResult(markdown)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to stop recording")
                    _errorMessage.value = "Failed to stop recording: ${e.message}"
                } finally {
                    currentRecordingTarget = null
                    currentRecordingFilename = null
                }
            }
        }

        fun cancelRecording() {
            val filename = currentRecordingFilename
            resetRecordingState()
            viewModelScope.launch {
                try {
                    recordingCoordinator.discardRecording(filename)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to discard canceled recording: %s", filename)
                }
            }
            currentRecordingTarget = null
            currentRecordingFilename = null
        }

        private fun resetRecordingState() {
            recordingJob?.cancel()
            _isRecording.value = false
            _recordingDuration.value = 0
            _recordingAmplitude.value = 0
        }

        fun clearError() {
            _errorMessage.value = null
        }

        override fun onCleared() {
            super.onCleared()
            recordingJob?.cancel()
            recordingCoordinator.stopSilently()
            currentRecordingTarget = null
            currentRecordingFilename = null
        }
    }
