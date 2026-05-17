/*
 * Test Contract:
 * - Unit under test: RecordingViewModelTest
 * - Owning layer: app
 * - Priority tier: P0
 *
 * Scenario matrix:
 * - Happy: standard happy path for RecordingViewModelTest.
 * - Boundary: boundary and edge cases for RecordingViewModelTest.
 * - Failure: failure and error scenarios for RecordingViewModelTest.
 * - Must-not-happen: invariants are never violated for RecordingViewModelTest.
 *
 * - Behavior focus: test behavioral outcomes of RecordingViewModelTest.
 * - Observable outcomes: assertions verify expected outcomes.
 * - Red phase: Fails before JUnit 4 to Kotest migration due to test runner.
 * - Excludes: none.
 */

package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: RecordingViewModel
 * - Behavior focus: recording lifecycle state transitions, markdown result creation, and failure cleanup.
 * - Observable outcomes: isRecording/duration/amplitude/errorMessage state and recording callback payloads.
 * - Excludes: actual audio capture implementation, filesystem writes, and Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }

private lateinit var recordingCoordinator: RecordingCoordinator

    init {
        beforeTest {
            recordingCoordinator = mockk(relaxed = true)
            every { recordingCoordinator.voiceDirectory() } returns flowOf("/voice")
            every { recordingCoordinator.currentAmplitude() } returns 42
            coEvery { recordingCoordinator.startRecording(any()) } returns "/voice/voice_20260324_100000.m4a"
            coEvery { recordingCoordinator.stopRecording() } returns Unit
            coEvery { recordingCoordinator.discardRecording(any()) } returns Unit
        }
    }

    init {
        test("startRecording enters recording mode with generated filename") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()

                ((viewModel.isRecording.value)) shouldBe true
                (viewModel.errorMessage.value) shouldBe (null)
                coVerify(exactly = 1) { recordingCoordinator.startRecording(match { it.startsWith("voice_") }) }

                viewModel.cancelRecording()
                advanceUntilIdle()
            }
        }
    }

    init {
        test("stopRecording returns markdown for current filename and resets state") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(recordingCoordinator)
                var resultMarkdown: String? = null

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.stopRecording { resultMarkdown = it }
                advanceUntilIdle()

                ((viewModel.isRecording.value)) shouldBe false
                (viewModel.recordingDuration.value) shouldBe (0L)
                (viewModel.recordingAmplitude.value) shouldBe (0)
                ((resultMarkdown?.startsWith("![voice](voice_") == true)) shouldBe true
                coVerify(exactly = 1) { recordingCoordinator.stopRecording() }
            }
        }
    }

    init {
        test("startRecording failure reports error and discards recording") {
            runTest(testDispatcher) {
                coEvery { recordingCoordinator.startRecording(any()) } throws IllegalStateException("mic unavailable")
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()

                ((viewModel.isRecording.value)) shouldBe false
                (viewModel.errorMessage.value) shouldBe ("Failed to start recording: mic unavailable")
                coVerify(exactly = 1) { recordingCoordinator.discardRecording(null) }
            }
        }
    }

    init {
        test("cancelRecording discards current filename and resets state") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.cancelRecording()
                advanceUntilIdle()

                ((viewModel.isRecording.value)) shouldBe false
                (viewModel.recordingDuration.value) shouldBe (0L)
                (viewModel.recordingAmplitude.value) shouldBe (0)
                coVerify(exactly = 1) { recordingCoordinator.discardRecording(any()) }
            }
        }
    }

    init {
        test("stopRecording failure reports error and still clears active state") {
            runTest(testDispatcher) {
                coEvery { recordingCoordinator.stopRecording() } throws IllegalStateException("stop failed")
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.stopRecording {}
                advanceUntilIdle()

                ((viewModel.isRecording.value)) shouldBe false
                (viewModel.recordingDuration.value) shouldBe (0L)
                (viewModel.recordingAmplitude.value) shouldBe (0)
                (viewModel.errorMessage.value) shouldBe ("Failed to stop recording: stop failed")
            }
        }
    }

    init {
        test("clearError removes existing failure message") {
            runTest(testDispatcher) {
                coEvery { recordingCoordinator.startRecording(any()) } throws IllegalStateException("mic unavailable")
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()
                (viewModel.errorMessage.value) shouldBe ("Failed to start recording: mic unavailable")

                viewModel.clearError()

                (viewModel.errorMessage.value) shouldBe (null)
            }
        }
    }

}
