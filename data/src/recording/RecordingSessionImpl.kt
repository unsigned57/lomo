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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
private const val VISUALIZER_UPDATE_INTERVAL_MILLIS = 50L
class RecordingSessionImpl
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
        private val transitionMutex = Mutex()
        private var phase: RecordingPhase = RecordingPhase.Idle
        private var timerJob: Job? = null
        override suspend fun startRecording() {
            transitionMutex.withLock {
                if (phase !is RecordingPhase.Idle) return
                phase = RecordingPhase.Starting
                _errorMessage.value = null
                val timestamp = VOICE_FILE_TIMESTAMP_FORMATTER.format(LocalDateTime.now())
                val filename = "voice_$timestamp.m4a"
                val entryId = MediaEntryId(filename)
                val startedAtMillis = System.currentTimeMillis()
                try {
                    val target = mediaRepository.allocateVoiceCaptureTarget(entryId).raw
                    voiceRecordingRepository.start(StorageLocation(target))
                    phase =
                        RecordingPhase.Recording(
                            filename = filename,
                            startedAtMillis = startedAtMillis,
                        )
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
                    resetSessionState()
                    stopAfterStartFailure(entryId)
                    throw cancellation
                } catch (error: Exception) {
                    Timber.e(error, "Failed to start recording")
                    _errorMessage.value = "Failed to start recording: ${error.message}"
                    resetSessionState()
                    stopAfterStartFailure(entryId)
                }
            }
        }
        override suspend fun stopRecording(): String? {
            return transitionMutex.withLock {
                val recordingState = phase as? RecordingPhase.Recording ?: return@withLock null
                phase = RecordingPhase.Stopping
                stopTimer()
                serviceController.stop()
                try {
                    voiceRecordingRepository.stop()
                    "![voice](${recordingState.filename})"
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.e(error, "Failed to stop recording")
                    _errorMessage.value = "Failed to stop recording: ${error.message}"
                    null
                } finally {
                    resetSessionState()
                }
            }
        }
        override suspend fun cancelRecording() {
            transitionMutex.withLock {
                val recordingState = phase as? RecordingPhase.Recording ?: return
                phase = RecordingPhase.Stopping
                stopTimer()
                serviceController.stop()
                // behavior-contract: silent-result-ok: discard is best-effort; partial file is logged on failure
                try {
                    voiceRecordingRepository.stop()
                    mediaRepository.removeVoiceCapture(MediaEntryId(recordingState.filename))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.w(error, "Failed to discard recording: %s", recordingState.filename)
                } finally {
                    resetSessionState()
                }
            }
        }
        override fun clearError() {
            _errorMessage.value = null
        }
        private suspend fun stopAfterStartFailure(entryId: MediaEntryId) {
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
            try {
                mediaRepository.removeVoiceCapture(entryId)
            } catch (error: Exception) {
                Timber.w(error, "Failed to remove voice capture after start failure: %s", entryId.raw)
            }
        }
        private fun resetSessionState() {
            phase = RecordingPhase.Idle
            _state.value = RecordingSessionState.Idle
            _durationMillis.value = 0
            _amplitude.value = 0
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
private sealed interface RecordingPhase {
    data object Idle : RecordingPhase
    data object Starting : RecordingPhase
    data class Recording(
        val filename: String,
        val startedAtMillis: Long,
    ) : RecordingPhase
    data object Stopping : RecordingPhase
}
