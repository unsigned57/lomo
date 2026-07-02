/*
 * Behavior Contract:
 * - Unit under test: RecordingSessionImpl
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: process-level recording state machine with deterministic markdown on stop and service-starter side effect.
 *
 * Scenarios:
 * - Given Idle, when startRecording, then state becomes Recording(filename, startedAtMillis) and foreground service starts.
 * - Given Recording, when stopRecording, then state returns to Idle, markdown is ![voice](filename), foreground service stops.
 * - Given Recording, when cancelRecording, then state returns to Idle, foreground service stops, no markdown produced.
 * - Given Idle, when stopRecording, then returns null without side effects (idempotent).
 * - Given Idle, when cancelRecording, then no side effects (idempotent).
 * - Given Recording, when startRecording, then no-op (idempotent singleton invariant: exactly one recording at a time).
 * - Given startRecording throws, when caught, then state stays Idle, errorMessage set, service not started.
 *
 * Observable outcomes: state transitions, returned markdown, service start/stop call counts, errorMessage.
 *
 * TDD proof: Fails before the fix because the stub returns null/Idle and never calls voice repo or service controller.
 *
 * Excludes: MediaRecorder platform behavior, foreground service lifecycle, notification building, VoiceRecordingRepository call counts (coverage instrumentation can double-call suspend functions; service controller call counts are stable as they are non-suspend).
 */

package com.lomo.data.recording

import com.lomo.data.repository.RecordingMediaRepository
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.repository.VoiceRecordingRepository
import com.lomo.domain.model.StorageLocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionImplTest : DataFunSpec() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val timerScheduler = TestCoroutineScheduler()
    private val timerDispatcher = StandardTestDispatcher(timerScheduler)

    private val voiceRecordingRepository = FakeVoiceRecordingRepository()
    private val mediaRepository = RecordingMediaRepository()
    private val serviceController = FakeRecordingServiceController()

    private val session = RecordingSessionImpl(
        appScope = testScope,
        voiceRecordingRepository = voiceRecordingRepository,
        mediaRepository = mediaRepository,
        serviceController = serviceController,
    ).also {
        it.recordingTimerDispatcher = timerDispatcher
    }

    init {
        test("startRecording transitions to Recording and starts foreground service") {
            runTest(testDispatcher) {
                session.startRecording()
                advanceUntilIdle()

                val state = session.state.value
                state.shouldBeInstanceOf<RecordingSessionState.Recording>()
                state.filename shouldNotBe null
                state.startedAtMillis shouldNotBe 0L
                serviceController.startCallCount shouldBe 1
            }
        }

        test("stopRecording returns markdown, returns to Idle, and stops foreground service") {
            runTest(testDispatcher) {
                session.startRecording()
                advanceUntilIdle()

                val recordingState = session.state.value as RecordingSessionState.Recording
                val markdown = session.stopRecording()
                advanceUntilIdle()

                markdown shouldBe "![voice](${recordingState.filename})"
                session.state.value.shouldBeInstanceOf<RecordingSessionState.Idle>()
                session.durationMillis.value shouldBe 0L
                session.amplitude.value shouldBe 0
                serviceController.stopCallCount shouldNotBe 0
            }
        }

        test("cancelRecording returns to Idle, stops foreground service, without markdown") {
            runTest(testDispatcher) {
                session.startRecording()
                advanceUntilIdle()

                session.cancelRecording()
                advanceUntilIdle()

                session.state.value.shouldBeInstanceOf<RecordingSessionState.Idle>()
                session.durationMillis.value shouldBe 0L
                session.amplitude.value shouldBe 0
                serviceController.stopCallCount shouldNotBe 0
            }
        }

        test("stopRecording while Idle is idempotent and returns null without side effects") {
            runTest(testDispatcher) {
                val markdown = session.stopRecording()

                markdown.shouldBeNull()
                session.state.value.shouldBeInstanceOf<RecordingSessionState.Idle>()
                serviceController.stopCallCount shouldBe 0
            }
        }

        test("cancelRecording while Idle is idempotent without side effects") {
            runTest(testDispatcher) {
                session.cancelRecording()

                session.state.value.shouldBeInstanceOf<RecordingSessionState.Idle>()
                serviceController.stopCallCount shouldBe 0
            }
        }

        test("startRecording while Recording is idempotent: does not start a second service") {
            runTest(testDispatcher) {
                session.startRecording()
                advanceUntilIdle()

                session.startRecording()
                advanceUntilIdle()

                serviceController.startCallCount shouldBe 1
            }
        }

        test("startRecording failure keeps state Idle, sets errorMessage, does not start service") {
            runTest(testDispatcher) {
                voiceRecordingRepository.startException = IllegalStateException("mic unavailable")

                session.startRecording()
                advanceUntilIdle()

                session.state.value.shouldBeInstanceOf<RecordingSessionState.Idle>()
                session.errorMessage.value shouldBe "Failed to start recording: mic unavailable"
                serviceController.startCallCount shouldBe 0
            }
        }
    }
}

private class FakeVoiceRecordingRepository : VoiceRecordingRepository {
    var startException: Throwable? = null

    override suspend fun start(outputLocation: StorageLocation) {
        startException?.let { throw it }
    }

    override suspend fun stop() {}

    override fun getAmplitude(): Int = 0
}

private class FakeRecordingServiceController : RecordingServiceController {
    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set

    override fun start() {
        startCallCount += 1
    }

    override fun stop() {
        stopCallCount += 1
    }
}
