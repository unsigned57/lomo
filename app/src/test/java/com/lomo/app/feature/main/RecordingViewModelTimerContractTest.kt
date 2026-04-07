package com.lomo.app.feature.main

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: RecordingViewModel
 * - Behavior focus: recording timer progression must follow the coroutine test scheduler instead of real time.
 * - Observable outcomes: recordingDuration and recordingAmplitude advance after virtual-time ticks and remain deterministic in tests.
 * - Red phase: Fails before the fix because RecordingViewModel runs its timer on Dispatchers.IO and reads System.currentTimeMillis(), so advancing the test scheduler does not reliably advance recording state.
 * - Excludes: actual audio capture, filesystem writes, filename formatting, and Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTimerContractTest {
    private val testDispatcher = StandardTestDispatcher()
    private val timerScheduler = TestCoroutineScheduler()
    private val timerDispatcher = StandardTestDispatcher(timerScheduler)
    private lateinit var recordingCoordinator: RecordingCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        recordingCoordinator = mockk(relaxed = true)
        every { recordingCoordinator.voiceDirectory() } returns flowOf("/voice")
        every { recordingCoordinator.currentAmplitude() } returns 42
        coEvery { recordingCoordinator.startRecording(any()) } returns "/voice/voice_20260324_100000.m4a"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startRecording timer advances with test scheduler ticks`() =
        runTest {
            val viewModel = RecordingViewModel(recordingCoordinator)
            viewModel.recordingTimerDispatcher = timerDispatcher

            viewModel.startRecording()
            advanceUntilIdle()

            assertEquals(0L, viewModel.recordingDuration.value)
            assertEquals(0, viewModel.recordingAmplitude.value)

            timerScheduler.advanceTimeBy(50)
            timerScheduler.runCurrent()

            assertEquals(50L, viewModel.recordingDuration.value)
            assertEquals(42, viewModel.recordingAmplitude.value)
        }
}
