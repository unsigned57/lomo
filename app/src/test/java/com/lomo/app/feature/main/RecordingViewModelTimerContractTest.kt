package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import io.kotest.matchers.shouldBe
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
 * Test Contract:
 * - Unit under test: RecordingViewModel
 * - Behavior focus: recording timer progression must follow the coroutine test scheduler instead of real time.
 * - Observable outcomes: recordingDuration and recordingAmplitude advance after virtual-time ticks and remain deterministic in tests.
 * - Red phase: Fails before the fix because RecordingViewModel runs its timer on Dispatchers.IO and reads System.currentTimeMillis(), so advancing the test scheduler does not reliably advance recording state.
 * - Excludes: actual audio capture, filesystem writes, filename formatting, and Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTimerContractTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }
    private val timerScheduler = TestCoroutineScheduler()
    private val timerDispatcher = StandardTestDispatcher(timerScheduler)
    private lateinit var recordingCoordinator: RecordingCoordinator

    init {
        beforeTest {
recordingCoordinator = mockk(relaxed = true)
            every { recordingCoordinator.voiceDirectory() } returns flowOf("/voice")
            every { recordingCoordinator.currentAmplitude() } returns 42
            coEvery { recordingCoordinator.startRecording(any()) } returns "/voice/voice_20260324_100000.m4a"
        }
    }

    init {
        test("startRecording timer advances with test scheduler ticks") {
            runTest {
                val viewModel = RecordingViewModel(recordingCoordinator)
                viewModel.recordingTimerDispatcher = timerDispatcher

                viewModel.startRecording()
                advanceUntilIdle()

                (viewModel.recordingDuration.value) shouldBe (0L)
                (viewModel.recordingAmplitude.value) shouldBe (0)

                timerScheduler.advanceTimeBy(50)
                timerScheduler.runCurrent()

                (viewModel.recordingDuration.value) shouldBe (50L)
                (viewModel.recordingAmplitude.value) shouldBe (42)
            }
        }
    }

}
