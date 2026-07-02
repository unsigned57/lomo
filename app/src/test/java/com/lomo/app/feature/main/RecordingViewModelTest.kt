/*
 * Behavior Contract:
 * - Unit under test: RecordingViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: RecordingViewModel delegates start/stop/cancel to RecordingSessionUseCase and exposes state to the UI.
 *
 * Scenarios:
 * - Given Idle session, when startRecording, then isRecording becomes true.
 * - Given Recording session, when stopRecording, then isRecording becomes false and onResult receives markdown.
 * - Given Recording session, when cancelRecording, then isRecording becomes false.
 * - Given session start failure, when startRecording, then isRecording stays false and errorMessage is set.
 * - Given errorMessage is set, when clearError, then errorMessage becomes null.
 *
 * Observable outcomes: isRecording, errorMessage, recordingDuration, recordingAmplitude, onResult callback.
 *
 * TDD proof: Static quality fails when RecordingViewModel depends directly on RecordingSession; tests keep the same observable UI delegation contract after moving the boundary to RecordingSessionUseCase.
 *
 * Excludes: RecordingSessionImpl internals (covered by RecordingSessionImplTest), MediaRecorder platform behavior.
 *
 * Test Change Justification:
 * - Reason category: architecture boundary migration.
 * - Old behavior/assertion being replaced: ViewModel tests used the Activity-scoped RecordingCoordinator path.
 * - Why old assertion is no longer correct: recording state is now process-scoped through RecordingSessionUseCase.
 * - Coverage preserved by: start, stop, cancel, error, and clear-error observable ViewModel scenarios remain covered.
 * - Why this is not fitting the test to the implementation: assertions stay at the ViewModel state/callback boundary.
 */

package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.usecase.RecordingSessionUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val fakeSession = FakeRecordingSession()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            fakeSession.reset()
        }

        test("startRecording enters recording mode") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))

                viewModel.startRecording()
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe true
                viewModel.errorMessage.value shouldBe null
            }
        }

        test("stopRecording returns markdown and resets state") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))
                var resultMarkdown: String? = null

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.stopRecording { resultMarkdown = it }
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.recordingDuration.value shouldBe 0L
                viewModel.recordingAmplitude.value shouldBe 0
                resultMarkdown?.startsWith("![voice](") shouldBe true
            }
        }

        test("startRecording failure reports error and stays Idle") {
            runTest(testDispatcher) {
                fakeSession.startException = IllegalStateException("mic unavailable")
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))

                viewModel.startRecording()
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.errorMessage.value shouldBe "Failed to start recording: mic unavailable"
            }
        }

        test("cancelRecording resets state") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.cancelRecording()
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.recordingDuration.value shouldBe 0L
                viewModel.recordingAmplitude.value shouldBe 0
            }
        }

        test("clearError removes existing failure message") {
            runTest(testDispatcher) {
                fakeSession.startException = IllegalStateException("mic unavailable")
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to start recording: mic unavailable"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }
    }
}

private class FakeRecordingSession : RecordingSession {
    private val _state = MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle)
    override val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    private val _durationMillis = MutableStateFlow(0L)
    override val durationMillis: StateFlow<Long> = _durationMillis.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    override val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var startException: Throwable? = null

    fun reset() {
        _state.value = RecordingSessionState.Idle
        _durationMillis.value = 0L
        _amplitude.value = 0
        _errorMessage.value = null
        startException = null
    }

    override suspend fun startRecording() {
        startException?.let {
            _errorMessage.value = "Failed to start recording: ${it.message}"
            return
        }
        _state.value =
            RecordingSessionState.Recording(
                filename = "voice_20260324_100000.m4a",
                startedAtMillis = System.currentTimeMillis(),
            )
        _durationMillis.value = 0
        _amplitude.value = 0
    }

    override suspend fun stopRecording(): String? {
        val recording = _state.value as? RecordingSessionState.Recording ?: return null
        _state.value = RecordingSessionState.Idle
        _durationMillis.value = 0
        _amplitude.value = 0
        return "![voice](${recording.filename})"
    }

    override suspend fun cancelRecording() {
        _state.value = RecordingSessionState.Idle
        _durationMillis.value = 0
        _amplitude.value = 0
    }

    override fun clearError() {
        _errorMessage.value = null
    }
}
