package com.lomo.data.recording

import com.lomo.data.di.ApplicationScope
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.repository.VoiceRecordingRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val VISUALIZER_UPDATE_INTERVAL_MILLIS = 50L

@Singleton
class RecordingSessionImpl
    @Inject
    constructor(
        @ApplicationScope private val appScope: CoroutineScope,
        private val voiceRecordingRepository: VoiceRecordingRepository,
        private val mediaRepository: MediaRepository,
        private val serviceController: RecordingServiceController,
    ) : RecordingSession {
        private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
        override val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

        private val _durationMillis = MutableStateFlow(0L)
        override val durationMillis: StateFlow<Long> = _durationMillis.asStateFlow()

        private val _amplitude = MutableStateFlow(0)
        override val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        internal var recordingTimerDispatcher: CoroutineDispatcher = Dispatchers.Default
        private var timerJob: Job? = null

        override suspend fun startRecording() {
            if (_state.value !is RecordingSessionState.Idle) return
            _errorMessage.value = null
            val timestamp = VOICE_FILE_TIMESTAMP_FORMATTER.format(LocalDateTime.now())
            val filename = "voice_$timestamp.m4a"
            val startedAtMillis = System.currentTimeMillis()
            try {
                val target = mediaRepository.allocateVoiceCaptureTarget(MediaEntryId(filename)).raw
                voiceRecordingRepository.start(StorageLocation(target))
                _state.value =
                    RecordingSessionState.Recording(
                        filename = filename,
                        startedAtMillis = startedAtMillis,
                    )
                _durationMillis.value = 0
                _amplitude.value = 0
                serviceController.start()
                startTimer()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.e(error, "Failed to start recording")
                _errorMessage.value = "Failed to start recording: ${error.message}"
                stopAfterStartFailure()
            }
        }

        override suspend fun stopRecording(): String? {
            val recordingState = _state.value as? RecordingSessionState.Recording ?: return null
            stopTimer()
            _state.value = RecordingSessionState.Idle
            _durationMillis.value = 0
            _amplitude.value = 0
            serviceController.stop()
            stopVoiceCaptureSafely("Failed to stop recording")
            return "![voice](${recordingState.filename})"
        }

        override suspend fun cancelRecording() {
            val recordingState = _state.value as? RecordingSessionState.Recording ?: return
            stopTimer()
            _state.value = RecordingSessionState.Idle
            _durationMillis.value = 0
            _amplitude.value = 0
            serviceController.stop()
            // behavior-contract: silent-result-ok: discard is best-effort; partial file is logged on failure
            try {
                voiceRecordingRepository.stop()
                mediaRepository.removeVoiceCapture(MediaEntryId(recordingState.filename))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "Failed to discard recording: %s", recordingState.filename)
            }
        }

        override fun clearError() {
            _errorMessage.value = null
        }

        private suspend fun stopVoiceCaptureSafely(message: String) {
            try {
                voiceRecordingRepository.stop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.e(error, message)
                _errorMessage.value = "$message: ${error.message}"
            }
        }

        private suspend fun stopAfterStartFailure() {
            // behavior-contract: silent-result-ok: best-effort cleanup after start failure; error is surfaced
            try {
                voiceRecordingRepository.stop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "Failed to stop recorder after start failure")
            }
            try {
                serviceController.stop()
            } catch (error: Exception) {
                Timber.w(error, "Failed to stop recording service after start failure")
            }
        }

        private fun startTimer() {
            timerJob?.cancel()
            timerJob =
                appScope.launch(recordingTimerDispatcher) {
                    while (isActive) {
                        delay(VISUALIZER_UPDATE_INTERVAL_MILLIS)
                        _durationMillis.value += VISUALIZER_UPDATE_INTERVAL_MILLIS
                        _amplitude.value = voiceRecordingRepository.getAmplitude()
                    }
                }
        }

        private fun stopTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        companion object {
            private val VOICE_FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        }
    }
