package com.lomo.app.feature.main

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Recording timer duration advances deterministically with the test dispatcher's virtual scheduler.
 * - Scenarios:
 *   - Given starting a recording with standard dispatcher replaced, confirm duration/amplitude starts at 0, and increments precisely with virtual time steps.
 * - Observable outcomes:
 *   - recordingDuration and recordingAmplitude matches advanced virtual scheduler duration and mocked amplitude return value.
 * - TDD proof: Confirms precise timer progression without dependency on physical host system clock.
 * - Excludes: Direct microphone inputs and disk operations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTimerContractTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val timerScheduler = TestCoroutineScheduler()
    private val timerDispatcher = StandardTestDispatcher(timerScheduler)
    private val recordingCoordinator: RecordingCoordinator = mockk()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            clearMocks(recordingCoordinator)
            every { recordingCoordinator.voiceDirectory() } returns flowOf("/voice")
            every { recordingCoordinator.currentAmplitude() } returns 42
            coEvery { recordingCoordinator.startRecording(any()) } returns "/voice/voice_20260324_100000.m4a"
        }

        test("startRecording timer advances with test scheduler ticks") {
            runTest {
                val viewModel = RecordingViewModel(recordingCoordinator)
                viewModel.recordingTimerDispatcher = timerDispatcher

                viewModel.startRecording()
                advanceUntilIdle()

                viewModel.recordingDuration.value shouldBe 0L
                viewModel.recordingAmplitude.value shouldBe 0

                timerScheduler.advanceTimeBy(50)
                timerScheduler.runCurrent()

                viewModel.recordingDuration.value shouldBe 50L
                viewModel.recordingAmplitude.value shouldBe 42
            }
        }
    }
}
