// adversarial-audit: RecordingSessionImpl.startRecording allocates a durable capture
// target via mediaRepository.allocateVoiceCaptureTarget and starts the recorder on it, but the
// failure path calls stopAfterStartFailure(entryId, captureLocation = null, draftId), so
// mediaRepository.removeVoiceCapture is skipped and the staged capture file leaks whenever a
// failure lands after allocation (for example serviceController.start() throwing when the
// microphone foreground service is denied). The removeVoiceCapture parameter exists precisely
// for this cleanup yet is unreachable in production.

package com.lomo.data.recording

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.DraftMediaReconciliation
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.MediaImageDescriptor
import com.lomo.domain.model.RecordingSessionState
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.VoiceRecordingRepository
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: RecordingSessionImpl.startRecording failure path.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: a durable capture target allocated before the recorder starts must be discarded
 *   when start fails; a failure after allocation can never leak a staged capture file.
 *
 * Scenarios:
 * - Given the capture target is allocated, when the recording service fails to start, then the
 *   staged capture is discarded via removeVoiceCapture.
 *
 * Observable outcomes: removeVoiceCapture invoked for the allocated entry; no leaked stage bytes.
 *
 * TDD proof:
 * - RED while the failure path skipped removeVoiceCapture (captureLocation = null); GREEN once the
 *   allocated target is discarded on every post-allocation failure.
 *
 * Excludes:
 * - Successful recording sessions and playback; foreground-service permission UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStartFailureCleanupContractTest : DataFunSpec() {
    init {
        test("given the capture target is allocated when the recording service fails to start then the staged capture is discarded") {
            runTest {
                val recorder = AuditVoiceRecordingRepository()
                val media = LeakTrackingMediaRepository()
                val serviceController = ThrowingStartServiceController()
                val session =
                    RecordingSessionImpl(
                        appScope = backgroundScope,
                        voiceRecordingRepository = recorder,
                        mediaRepository = media,
                        serviceController = serviceController,
                    )

                session.startRecording()

                session.state.value shouldBe RecordingSessionState.Idle
                session.errorMessage.value.shouldNotBeNull()
                recorder.stopCallCount shouldBe 1
                serviceController.stopCallCount shouldBe 1

                // The recorder already opened the allocated target; the failed start must hand
                // that exact capture to removeVoiceCapture instead of leaking a partial file.
                media.allocatedEntryIds.shouldNotBeEmpty()
                media.removedCaptures.shouldNotBeEmpty()
                media.removedCaptures.map { it.entryId } shouldBe media.allocatedEntryIds
            }
        }
    }

    private data class RemovedCapture(
        val entryId: MediaEntryId,
        val captureLocation: StorageLocation,
        val draftId: DraftId,
    )

    private class AuditVoiceRecordingRepository : VoiceRecordingRepository {
        var stopCallCount = 0
            private set

        override suspend fun start(outputLocation: StorageLocation) = Unit

        override suspend fun stop() {
            stopCallCount += 1
        }

        override fun sampleAmplitude(): Int? = null

        override fun captureFailure(): Throwable? = null
    }

    private class ThrowingStartServiceController : RecordingServiceController {
        var stopCallCount = 0
            private set

        override fun start() {
            throw IllegalStateException("mic foreground service start denied")
        }

        override fun stop() {
            stopCallCount += 1
        }
    }

    private class LeakTrackingMediaRepository : MediaRepository {
        val allocatedEntryIds = mutableListOf<MediaEntryId>()
        val removedCaptures = mutableListOf<RemovedCapture>()
        private val locations = MutableStateFlow<Map<MediaEntryId, MediaImageDescriptor>>(emptyMap())

        override suspend fun importImage(
            source: StorageLocation,
            draftId: DraftId,
        ): StorageLocation = source

        override suspend fun removeImage(
            entryId: MediaEntryId,
            draftId: DraftId,
        ) = Unit

        override fun observeImageLocations(): Flow<Map<MediaEntryId, MediaImageDescriptor>> = locations

        override suspend fun refreshImageLocations() = Unit

        override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? = null

        override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation {
            allocatedEntryIds += entryId
            return StorageLocation("file:///tmp/stage/${entryId.raw}")
        }

        override suspend fun finalizeVoiceCapture(
            recordingLocation: StorageLocation,
            humanNameHint: String,
            draftId: DraftId,
        ): StorageLocation = StorageLocation("media/${humanNameHint.ifBlank { "voice.m4a" }}")

        override suspend fun removeVoiceCapture(
            entryId: MediaEntryId,
            captureLocation: StorageLocation,
            draftId: DraftId,
        ) {
            removedCaptures += RemovedCapture(entryId, captureLocation, draftId)
        }

        override suspend fun runOrphanSweepAtOperationBoundary() = Unit

        override suspend fun reconcileDraftMedia(draftId: DraftId): DraftMediaReconciliation =
            DraftMediaReconciliation(draftId = draftId, records = emptyList())

        override suspend fun releaseDraftLeases(draftId: DraftId) = Unit
    }
}
