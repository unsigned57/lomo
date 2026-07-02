/*
 * Behavior Contract:
 * - Unit under test: RecordingViewModel (duration delegation)
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: RecordingViewModel exposes RecordingSessionUseCase duration and amplitude without modification.
 *
 * Scenarios:
 * - Given session use case reports duration 50L, when observed, then viewModel.recordingDuration.value is 50L.
 * - Given session use case reports amplitude 42, when observed, then viewModel.recordingAmplitude.value is 42.
 *
 * Observable outcomes: recordingDuration value matches session's durationMillis.
 *
 * TDD proof: Static quality fails when RecordingViewModel depends directly on RecordingSession; tests preserve the observable delegation contract after moving the dependency to RecordingSessionUseCase.
 *
 * Excludes: Timer coroutine internals (covered by RecordingSessionImplTest).
 *
 * Test Change Justification:
 * - Reason category: architecture boundary migration.
 * - Old behavior/assertion being replaced: duration/amplitude delegation was tied to RecordingSession directly.
 * - Why old assertion is no longer correct: ViewModel now consumes RecordingSessionUseCase to satisfy app architecture boundaries.
 * - Coverage preserved by: duration and amplitude observable delegation scenarios remain covered.
 * - Why this is not fitting the test to the implementation: assertions verify unchanged UI-facing StateFlow values.
 */

package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.repository.RecordingSession
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.usecase.RecordingSessionUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTimerContractTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        test("recordingDuration delegates to RecordingSession durationMillis") {
            runTest(testDispatcher) {
                val fakeSession = FakeDurationSession(durationMillis = 50L)
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))

                viewModel.recordingDuration.value shouldBe 50L
            }
        }

        test("recordingAmplitude delegates to RecordingSession amplitude") {
            runTest(testDispatcher) {
                val fakeSession = FakeDurationSession(amplitude = 42)
                val viewModel = RecordingViewModel(RecordingSessionUseCase(fakeSession))

                viewModel.recordingAmplitude.value shouldBe 42
            }
        }
    }
}

private class FakeDurationSession(
    durationMillis: Long = 0L,
    amplitude: Int = 0,
) : RecordingSession {
    override val state: StateFlow<RecordingSessionState> =
        MutableStateFlow<RecordingSessionState>(RecordingSessionState.Idle).asStateFlow()

    override val durationMillis: StateFlow<Long> = MutableStateFlow(durationMillis).asStateFlow()

    override val amplitude: StateFlow<Int> = MutableStateFlow(amplitude).asStateFlow()

    override val errorMessage: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    override suspend fun startRecording() {}

    override suspend fun stopRecording(): String? = null

    override suspend fun cancelRecording() {}

    override fun clearError() {}
}
