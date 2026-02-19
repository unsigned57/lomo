package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.VoiceRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private val repository: MemoRepository,
        private val mediaRepository: MediaRepository,
        private val voiceRecorder: VoiceRecorder,
    ) : ViewModel() {
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _recordingDuration = MutableStateFlow(0L)
        val recordingDuration: StateFlow<Long> = _recordingDuration

        private val _recordingAmplitude = MutableStateFlow(0)
        val recordingAmplitude: StateFlow<Int> = _recordingAmplitude

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        val voiceDirectory: StateFlow<String?> =
            repository
                .getVoiceDirectory()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        private var recordingJob: kotlinx.coroutines.Job? = null
        private var currentRecordingUri: android.net.Uri? = null
        private var currentRecordingFilename: String? = null

        fun startRecording() {
            viewModelScope.launch {
                try {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val filename = "voice_$timestamp.m4a"

                    // 1. Create file via repository (handles Voice Backend logic)
                    val uri = mediaRepository.createVoiceFile(filename)

                    currentRecordingUri = uri
                    currentRecordingFilename = filename

                    // 2. Start recording to the file URI
                    voiceRecorder.start(uri)
                    _isRecording.value = true
                    _recordingDuration.value = 0

                    startRecordingTimer()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to start recording: ${e.message}"
                    cancelRecording()
                }
            }
        }

        private fun startRecordingTimer() {
            recordingJob?.cancel()
            recordingJob =
                viewModelScope.launch {
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

            try {
                voiceRecorder.stop()
                recordingJob?.cancel()
                _isRecording.value = false
                _recordingDuration.value = 0
                _recordingAmplitude.value = 0

                val uri = currentRecordingUri
                val filename = currentRecordingFilename

                if (uri != null && filename != null) {
                    // Use just the filename - voice directory is resolved by AudioPlayerManager
                    val markdown = "![voice]($filename)"
                    onResult(markdown)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Failed to stop recording: ${e.message}"
            }
            currentRecordingUri = null
            currentRecordingFilename = null
        }

        fun cancelRecording() {
            try {
                voiceRecorder.stop()
                recordingJob?.cancel()
                _isRecording.value = false
                _recordingDuration.value = 0
                _recordingAmplitude.value = 0

                val filename = currentRecordingFilename
                if (filename != null) {
                    viewModelScope.launch {
                        try {
                            mediaRepository.deleteVoiceFile(filename)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                currentRecordingUri = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
            currentRecordingUri = null
            currentRecordingFilename = null
        }

        fun clearError() {
            _errorMessage.value = null
        }

        override fun onCleared() {
            super.onCleared()
            cancelRecording() // Safety cleanup
        }
    }
