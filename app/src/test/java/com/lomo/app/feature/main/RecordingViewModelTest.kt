package com.lomo.app.feature.main

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: RecordingViewModel
 * - Behavior focus: recording lifecycle state transitions, markdown result creation, and failure cleanup.
 * - Observable outcomes: isRecording/duration/amplitude/errorMessage state and recording callback payloads.
 * - Excludes: actual audio capture implementation, filesystem writes, and Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var recordingCoordinator: RecordingCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recordingCoordinator = mockk(relaxed = true)
        every { recordingCoordinator.voiceDirectory() } returns flowOf("/voice")
        every { recordingCoordinator.currentAmplitude() } returns 42
        coEvery { recordingCoordinator.startRecording(any()) } returns "/voice/voice_20260324_100000.m4a"
        coEvery { recordingCoordinator.stopRecording() } returns Unit
        coEvery { recordingCoordinator.discardRecording(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startRecording enters recording mode with generated filename`() =
        runTest {
            val viewModel = RecordingViewModel(recordingCoordinator)

            viewModel.startRecording()
            advanceUntilIdle()

            assertTrue(viewModel.isRecording.value)
            assertEquals(null, viewModel.errorMessage.value)
            coVerify(exactly = 1) { recordingCoordinator.startRecording(match { it.startsWith("voice_") }) }

            viewModel.cancelRecording()
            advanceUntilIdle()
        }

    @Test
    fun `stopRecording returns markdown for current filename and resets state`() =
        runTest {
            val viewModel = RecordingViewModel(recordingCoordinator)
            var resultMarkdown: String? = null

            viewModel.startRecording()
            advanceUntilIdle()
            viewModel.stopRecording { resultMarkdown = it }
            advanceUntilIdle()

            assertFalse(viewModel.isRecording.value)
            assertEquals(0L, viewModel.recordingDuration.value)
            assertEquals(0, viewModel.recordingAmplitude.value)
            assertTrue(resultMarkdown?.startsWith("![voice](voice_") == true)
            coVerify(exactly = 1) { recordingCoordinator.stopRecording() }
        }

    @Test
    fun `startRecording failure reports error and discards recording`() =
        runTest {
            coEvery { recordingCoordinator.startRecording(any()) } throws IllegalStateException("mic unavailable")
            val viewModel = RecordingViewModel(recordingCoordinator)

            viewModel.startRecording()
            advanceUntilIdle()

            assertFalse(viewModel.isRecording.value)
            assertEquals("Failed to start recording: mic unavailable", viewModel.errorMessage.value)
            coVerify(exactly = 1) { recordingCoordinator.discardRecording(null) }
        }

    @Test
    fun `cancelRecording discards current filename and resets state`() =
        runTest {
            val viewModel = RecordingViewModel(recordingCoordinator)

            viewModel.startRecording()
            advanceUntilIdle()
            viewModel.cancelRecording()
            advanceUntilIdle()

            assertFalse(viewModel.isRecording.value)
            assertEquals(0L, viewModel.recordingDuration.value)
            assertEquals(0, viewModel.recordingAmplitude.value)
            coVerify(exactly = 1) { recordingCoordinator.discardRecording(any()) }
        }

    @Test
    fun `stopRecording failure reports error and still clears active state`() =
        runTest {
            coEvery { recordingCoordinator.stopRecording() } throws IllegalStateException("stop failed")
            val viewModel = RecordingViewModel(recordingCoordinator)

            viewModel.startRecording()
            advanceUntilIdle()
            viewModel.stopRecording {}
            advanceUntilIdle()

            assertFalse(viewModel.isRecording.value)
            assertEquals(0L, viewModel.recordingDuration.value)
            assertEquals(0, viewModel.recordingAmplitude.value)
            assertEquals("Failed to stop recording: stop failed", viewModel.errorMessage.value)
        }

    @Test
    fun `clearError removes existing failure message`() =
        runTest {
            coEvery { recordingCoordinator.startRecording(any()) } throws IllegalStateException("mic unavailable")
            val viewModel = RecordingViewModel(recordingCoordinator)

            viewModel.startRecording()
            advanceUntilIdle()
            assertEquals("Failed to start recording: mic unavailable", viewModel.errorMessage.value)

            viewModel.clearError()

            assertEquals(null, viewModel.errorMessage.value)
        }
}
