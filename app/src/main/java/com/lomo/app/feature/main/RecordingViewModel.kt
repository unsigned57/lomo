package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.device.VoiceRecorder
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import timber.log.Timber

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
        private val settingsRepository: DirectorySettingsRepository,
        private val mediaRepository: MediaRepository,
        private val voiceRecorder: VoiceRecorder,
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
            settingsRepository
                .getVoiceDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private var recordingJob: kotlinx.coroutines.Job? = null
        private var currentRecordingTarget: String? = null
        private var currentRecordingFilename: String? = null

        fun startRecording() {
            viewModelScope.launch {
                try {
                    val timestamp = VOICE_FILE_TIMESTAMP_FORMATTER.format(LocalDateTime.now())
                    val filename = "voice_$timestamp.m4a"

                    // 1. Create file via repository (handles Voice Backend logic)
                    val target =
                        withContext(Dispatchers.IO) {
                            mediaRepository.createVoiceFile(filename)
                        }

                    currentRecordingTarget = target
                    currentRecordingFilename = filename

                    // 2. Start recording to the file URI
                    withContext(Dispatchers.IO) {
                        voiceRecorder.start(target)
                    }
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
                        _recordingAmplitude.value = voiceRecorder.getAmplitude()
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
                    withContext(Dispatchers.IO) {
                        voiceRecorder.stop()
                    }
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
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    voiceRecorder.stop()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to stop recorder during cancel")
                }
                if (!filename.isNullOrBlank()) {
                    try {
                        mediaRepository.deleteVoiceFile(filename)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to delete canceled recording file: %s", filename)
                    }
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
            try {
                voiceRecorder.stop()
            } catch (_: Exception) {
                // Best-effort shutdown.
            }
            currentRecordingTarget = null
            currentRecordingFilename = null
        }
    }
