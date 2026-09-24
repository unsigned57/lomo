package com.lomo.data.recording
import com.lomo.data.di.ApplicationScope
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.repository.VoiceRecordingRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
        dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ) : RecordingSession {
        private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
        override val state: StateFlow<RecordingSessionState> = _state.asStateFlow()
        private val _durationMillis = MutableStateFlow(0L)
        override val durationMillis: StateFlow<Long> = _durationMillis.asStateFlow()
        private val _amplitude = MutableStateFlow<Int?>(null)
        override val amplitude: StateFlow<Int?> = _amplitude.asStateFlow()
        private val _errorMessage = MutableStateFlow<String?>(null)
        override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
        internal var recordingTimerDispatcher: CoroutineDispatcher = dispatcherProvider.default
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
                // One durable holder identity per capture; its lease transfers on the created memo.
                val draftId = DraftId("recording-$startedAtMillis")
                try {
                    val target = mediaRepository.allocateVoiceCaptureTarget(entryId).raw
                    voiceRecordingRepository.start(StorageLocation(target))
                    phase =
                        RecordingPhase.Recording(
                            filename = filename,
                            captureLocation = target,
                            startedAtMillis = startedAtMillis,
                            draftId = draftId,
                        )
                    _state.value =
                        RecordingSessionState.Recording(
                            filename = filename,
                            startedAtMillis = startedAtMillis,
                            draftId = draftId,
                        )
                    _durationMillis.value = 0
                    _amplitude.value = null
                    serviceController.start()
                    startTimer()
                } catch (cancellation: CancellationException) {
                    resetSessionState()
                    stopAfterStartFailure(entryId, null, draftId)
                    throw cancellation
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.e(error, "Failed to start recording")
                    _errorMessage.value = "Failed to start recording: ${error.message}"
                    resetSessionState()
                    stopAfterStartFailure(entryId, null, draftId)
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
                    // D4: finalize stages only; promote is memo-bound under same operation-id.
                    val dest =
                        mediaRepository
                            .finalizeVoiceCapture(
                                recordingLocation = StorageLocation(recordingState.captureLocation),
                                humanNameHint = recordingState.filename,
                                draftId = recordingState.draftId,
                            ).raw
                    "![voice]($dest)"
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.e(error, "Failed to stop recording")
                    _errorMessage.value = "Failed to stop recording: ${error.message}"
                    // behavior-contract: silent-result-ok: stop failure is surfaced via errorMessage; null skips insert
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
                    mediaRepository.removeVoiceCapture(
                        entryId = MediaEntryId(recordingState.filename),
                        captureLocation = StorageLocation(recordingState.captureLocation),
                        draftId = recordingState.draftId,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.w(error, "Failed to discard recording: %s", recordingState.filename)
                } finally {
                    resetSessionState()
                }
            }
        }
        override fun clearError() {
            _errorMessage.value = null
        }
        private suspend fun stopAfterStartFailure(
            entryId: MediaEntryId,
            captureLocation: String?,
            draftId: DraftId,
        ) {
            // behavior-contract: silent-result-ok: best-effort cleanup after start failure; error is surfaced
            bestEffortCleanup("Failed to stop recorder after start failure") {
                voiceRecordingRepository.stop()
            }
            bestEffortCleanup("Failed to stop recording service after start failure") {
                serviceController.stop()
            }
            if (captureLocation != null) {
                bestEffortCleanup("Failed to remove voice capture after start failure: ${entryId.raw}") {
                    mediaRepository.removeVoiceCapture(
                        entryId = entryId,
                        captureLocation = StorageLocation(captureLocation),
                        draftId = draftId,
                    )
                }
            }
        }

        private suspend fun bestEffortCleanup(
            message: String,
            action: suspend () -> Unit,
        ) {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, message)
            }
        }
        private fun resetSessionState() {
            phase = RecordingPhase.Idle
            _state.value = RecordingSessionState.Idle
            _durationMillis.value = 0
            _amplitude.value = null
        }
        private fun startTimer() {
            timerJob?.cancel()
            timerJob =
                appScope.launch(recordingTimerDispatcher) {
                    while (isActive) {
                        delay(VISUALIZER_UPDATE_INTERVAL_MILLIS)
                        // behavior-contract: loop-io-ok: per-tick failure poll; no push channel
                        val captureFailure = voiceRecordingRepository.captureFailure()
                        if (captureFailure != null) {
                            failActiveCapture(captureFailure)
                            break
                        }
                        _durationMillis.value += VISUALIZER_UPDATE_INTERVAL_MILLIS
                        // behavior-contract: loop-io-ok: no bulk amplitude API; each iteration is one meter sample
                        _amplitude.value = voiceRecordingRepository.sampleAmplitude()
                    }
                }
        }

        private suspend fun failActiveCapture(failure: Throwable) {
            transitionMutex.withLock {
                val recordingState = phase as? RecordingPhase.Recording ?: return
                phase = RecordingPhase.Stopping
                // The calling timer loop breaks right after; cancelling it here would abort cleanup.
                serviceController.stop()
                _errorMessage.value = "Recording failed: ${failure.message}"
                bestEffortCleanup("Failed to stop recorder after capture failure") {
                    voiceRecordingRepository.stop()
                }
                bestEffortCleanup("Failed to discard capture after device failure: ${recordingState.filename}") {
                    mediaRepository.removeVoiceCapture(
                        entryId = MediaEntryId(recordingState.filename),
                        captureLocation = StorageLocation(recordingState.captureLocation),
                        draftId = recordingState.draftId,
                    )
                }
                resetSessionState()
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
        /** Absolute or file:// capture target from allocateVoiceCaptureTarget (stage only). */
        val captureLocation: String,
        val startedAtMillis: Long,
        val draftId: DraftId,
    ) : RecordingPhase
    data object Stopping : RecordingPhase
}
