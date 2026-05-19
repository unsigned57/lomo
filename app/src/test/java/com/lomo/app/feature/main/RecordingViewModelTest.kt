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
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.VoiceRecordingRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()
    private val appConfigRepository = FakeAppConfigRepository()
    private val mediaRepository = FakeMediaRepository()
    private val voiceRecordingRepository = FakeVoiceRecordingRepository()

    private val recordingCoordinator = FakeRecordingCoordinator(
        directorySettingsRepository = appConfigRepository,
        mediaRepository = mediaRepository,
        voiceRecordingRepository = voiceRecordingRepository
    )

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            appConfigRepository.setLocation(StorageArea.VOICE, StorageLocation("/voice"))
            mediaRepository.reset()
            voiceRecordingRepository.reset()
            recordingCoordinator.reset()
        }

        test("startRecording enters recording mode with generated filename") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe true
                viewModel.errorMessage.value shouldBe null
                mediaRepository.allocateVoiceCalled?.startsWith("voice_") shouldBe true

                viewModel.cancelRecording()
                advanceUntilIdle()
            }
        }

        test("stopRecording returns markdown for current filename and resets state") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(recordingCoordinator)
                var resultMarkdown: String? = null

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.stopRecording { resultMarkdown = it }
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.recordingDuration.value shouldBe 0L
                viewModel.recordingAmplitude.value shouldBe 0
                resultMarkdown?.startsWith("![voice](voice_") shouldBe true
                recordingCoordinator.stopCalledCount shouldBe 1
            }
        }

        test("startRecording failure reports error and discards recording") {
            runTest(testDispatcher) {
                recordingCoordinator.startException = IllegalStateException("mic unavailable")
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.errorMessage.value shouldBe "Failed to start recording: mic unavailable"
                mediaRepository.removeVoiceCaptureCalled shouldBe null
            }
        }

        test("cancelRecording discards current filename and resets state") {
            runTest(testDispatcher) {
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()
                val filename = mediaRepository.allocateVoiceCalled
                viewModel.cancelRecording()
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.recordingDuration.value shouldBe 0L
                viewModel.recordingAmplitude.value shouldBe 0
                mediaRepository.removeVoiceCaptureCalled shouldBe filename
            }
        }

        test("stopRecording failure reports error and still clears active state") {
            runTest(testDispatcher) {
                recordingCoordinator.stopException = IllegalStateException("stop failed")
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.stopRecording {}
                advanceUntilIdle()

                viewModel.isRecording.value shouldBe false
                viewModel.recordingDuration.value shouldBe 0L
                viewModel.recordingAmplitude.value shouldBe 0
                viewModel.errorMessage.value shouldBe "Failed to stop recording: stop failed"
            }
        }

        test("clearError removes existing failure message") {
            runTest(testDispatcher) {
                recordingCoordinator.startException = IllegalStateException("mic unavailable")
                val viewModel = RecordingViewModel(recordingCoordinator)

                viewModel.startRecording()
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "Failed to start recording: mic unavailable"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }
    }

    class FakeRecordingCoordinator(
        directorySettingsRepository: com.lomo.domain.repository.DirectorySettingsRepository,
        private val mediaRepository: MediaRepository,
        private val voiceRecordingRepository: VoiceRecordingRepository,
    ) : RecordingCoordinator(directorySettingsRepository, mediaRepository, voiceRecordingRepository) {
        var startException: Throwable? = null
        var stopException: Throwable? = null
        var stopCalledCount = 0
        var discardCalledWith: String? = null

        fun reset() {
            startException = null
            stopException = null
            stopCalledCount = 0
            discardCalledWith = null
        }

        override fun voiceDirectory(): Flow<String?> = kotlinx.coroutines.flow.flowOf("/voice")

        override suspend fun startRecording(filename: String): String {
            startException?.let { throw it }
            val target = mediaRepository.allocateVoiceCaptureTarget(MediaEntryId(filename)).raw
            voiceRecordingRepository.start(StorageLocation(target))
            return target
        }

        override suspend fun stopRecording() {
            stopException?.let { throw it }
            voiceRecordingRepository.stop()
            stopCalledCount++
        }

        override fun currentAmplitude(): Int = voiceRecordingRepository.getAmplitude()

        override suspend fun discardRecording(filename: String?) {
            voiceRecordingRepository.stop()
            discardCalledWith = filename
            if (!filename.isNullOrBlank()) {
                mediaRepository.removeVoiceCapture(MediaEntryId(filename))
            }
        }

        override fun stopSilently() {
            voiceRecordingRepository.stop()
        }
    }

    class FakeMediaRepository : MediaRepository {
        var allocateVoiceCalled: String? = null
        var removeVoiceCaptureCalled: String? = null

        fun reset() {
            allocateVoiceCalled = null
            removeVoiceCaptureCalled = null
        }

        override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation {
            allocateVoiceCalled = entryId.raw
            return StorageLocation("/voice/${entryId.raw}.m4a")
        }

        override suspend fun removeVoiceCapture(entryId: MediaEntryId) {
            removeVoiceCaptureCalled = entryId.raw
        }

        override suspend fun importImage(source: StorageLocation): StorageLocation = TODO()
        override suspend fun removeImage(entryId: MediaEntryId) = TODO()
        override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = TODO()
        override suspend fun refreshImageLocations() = TODO()
        override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = TODO()
    }

    class FakeVoiceRecordingRepository : VoiceRecordingRepository {
        var startCalledWith: StorageLocation? = null
        var stopCalledCount = 0
        var currentAmplitudeValue = 42
        var startException: Throwable? = null
        var stopException: Throwable? = null

        fun reset() {
            startCalledWith = null
            stopCalledCount = 0
            currentAmplitudeValue = 42
            startException = null
            stopException = null
        }

        override fun start(outputLocation: StorageLocation) {
            startException?.let { throw it }
            startCalledWith = outputLocation
        }

        override fun stop() {
            stopException?.let { throw it }
            stopCalledCount++
        }

        override fun getAmplitude(): Int = currentAmplitudeValue
    }
}


