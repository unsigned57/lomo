/*
 * Behavior Contract:
 * - Unit under test: MemoEditorViewModel.
 * - Owning layer: app.
 * - Priority tier: P1.
 * - Capability: own draft persistence and an acknowledged memo submission state machine.
 * - Scenarios:
 *   - Given starting a memo draft, save/clear persists state to storage.
 *   - Given constructor is called, it does not block on first persisted draft emission.
 *   - Given a create/update is still executing, submission remains Submitting and callers cannot
 *     observe success early; after the durable use case returns it becomes Committed.
 *   - Given createMemo/updateMemo success, discard inputs and clear draft text.
 *   - Given create/update failure, submission becomes Failed and the draft remains available.
 *   - Given a submission that never reaches a terminal state, when the acknowledgement budget
 *     elapses, then a stalled diagnostic is recorded (the only trace of an editor that can never
 *     close because nothing ever throws).
 *   - Given saveImage success or failure, manage tracked image list and propagate error states appropriately.
 * - Observable outcomes:
 *   - draftText/errorMessage/submissionState, terminal await result, and use-case payloads.
 * - TDD proof:
 *   - RED on 2026-08-09 because createMemo/updateMemo were fire-and-forget and exposed no state
 *     distinguishing an accepted click from a durable commit or failure.
 * - Excludes: actual widgets UI layout and Compose rendering components.
 *
 * Test Change Justification:
 * - Reason category: editor submission state machine and engine diagnostics integration.
 * - Old behavior/assertion being replaced: fire-and-forget submission without stalled diagnostics.
 * - Why old assertion is no longer correct: submissions require deterministic acknowledgement and stalled diagnostics.
 * - Coverage preserved by: all draft persistence, submission state machine, and error handling scenarios remain fully tested.
 * - Why this is not fitting the test to the implementation: verifies submission state transitions and timeouts.
 */

package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardDraftMediaUseCase
import com.lomo.domain.usecase.LoadCreateDraftUseCase
import com.lomo.domain.usecase.SaveCreateDraftUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val sharedDraftText = MutableStateFlow<String?>("initial draft")

    private val createMemoUseCase = FakeCreateMemoUseCase()
    private val updateMemoContentUseCase = FakeUpdateMemoContentUseCase()
    private val saveImageUseCase = FakeSaveImageUseCase()
    private val discardDraftMediaUseCase = FakeDiscardDraftMediaUseCase()
    private val loadCreateDraftUseCase = FakeLoadCreateDraftUseCase(sharedDraftText)
    private val saveCreateDraftUseCase = FakeSaveCreateDraftUseCase(sharedDraftText)
    private val diagnostics = FakeEngineDiagnosticsRecorder()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            sharedDraftText.value = "initial draft"
            createMemoUseCase.reset()
            updateMemoContentUseCase.reset()
            saveImageUseCase.reset()
            discardDraftMediaUseCase.reset()
            loadCreateDraftUseCase.reset()
            saveCreateDraftUseCase.reset()
            diagnostics.reset()
        }

        test("saveDraft updates local draft state and persists text") {
            runTest {
                val viewModel = createViewModel()

                viewModel.saveDraft("draft A")
                advanceUntilIdle()

                viewModel.draftText.value shouldBe "draft A"
                saveCreateDraftUseCase.saveCalledWithValue shouldBe "draft A"
                saveCreateDraftUseCase.saveCalledCount shouldBe 1
            }
        }

        test("clearDraft clears local state and persists null") {
            runTest {
                val viewModel = createViewModel()

                viewModel.clearDraft()
                advanceUntilIdle()

                viewModel.draftText.value shouldBe ""
                saveCreateDraftUseCase.saveCalledWithValue shouldBe null
            saveCreateDraftUseCase.saveCalledCount shouldBe 1
        }

        }

        test("constructor does not wait for the persisted create draft") {
            runTest {
                sharedDraftText.value = "loaded draft"
                val firstDraftGate = CompletableDeferred<Unit>()
                loadCreateDraftUseCase.loadGate = firstDraftGate
                val executor = Executors.newSingleThreadExecutor()

                try {
                    val future = executor.submit<MemoEditorViewModel> { createViewModel() }
                    val viewModel = future.get(200, TimeUnit.MILLISECONDS)

                    viewModel.draftText.value shouldBe ""

                    firstDraftGate.complete(Unit)
                    advanceUntilIdle()

                    viewModel.draftText.value shouldBe "loaded draft"
                } finally {
                    executor.shutdownNow()
                }
            }
        }

        test("createMemo success clears tracked images and draft") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-1"
                saveImageUseCase.customSaveResults["content://memo-editor/image-1"] =
                    SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-1.jpg"))

                viewModel.saveDraft("to be cleared")
                viewModel.saveImage(uri, onResult = {}, onError = null)
                advanceUntilIdle()

                val submissionId = MemoEditorSubmissionId(1L)
                viewModel.submissions.create(submissionId = submissionId, content = "new memo")
                advanceUntilIdle()

                viewModel.discardInputs()
                advanceUntilIdle()

                viewModel.submissions.await(submissionId) shouldBe true
                viewModel.draftText.value shouldBe ""
                createMemoUseCase.createMemoCalledWithContent shouldBe "new memo"
                saveCreateDraftUseCase.saveCalledWithValue shouldBe null
                discardDraftMediaUseCase.discardCalledWith shouldBe emptyList()
            }
        }

        test("create submission stays pending until the durable use case commits") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                createMemoUseCase.createMemoGate = gate
                val viewModel = createViewModel()
                val submissionId = MemoEditorSubmissionId(101L)

                viewModel.submissions.create(submissionId = submissionId, content = "new memo")
                val terminal = async { viewModel.submissions.await(submissionId) }
                testScheduler.runCurrent()

                viewModel.submissionState.value shouldBe MemoEditorSubmissionState.Submitting(submissionId)
                terminal.isCompleted shouldBe false

                gate.complete(Unit)
                advanceUntilIdle()

                terminal.await() shouldBe true
                viewModel.submissionState.value shouldBe MemoEditorSubmissionState.Committed(submissionId)
            }
        }

        test("createMemo failure surfaces throwable message and preserves draft") {
            runTest {
                createMemoUseCase.createMemoException = IllegalStateException("create failed")
                val viewModel = createViewModel()
                viewModel.saveDraft("keep me")
                advanceUntilIdle()

                viewModel.submissions.create(
                    submissionId = MemoEditorSubmissionId(102L),
                    content = "new memo",
                )
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "create failed"
                viewModel.draftText.value shouldBe "keep me"
                viewModel.submissionState.value shouldBe
                    MemoEditorSubmissionState.Failed(MemoEditorSubmissionId(102L))
            }
        }

        test("given a failed create when its unchanged draft retries then identity and chronology are preserved") {
            runTest {
                val viewModel = createViewModel()
                createMemoUseCase.createMemoException = IllegalStateException("host disconnected")
                viewModel.submissions.create(MemoEditorSubmissionId(700L), "same draft")
                advanceUntilIdle()
                createMemoUseCase.createMemoException = null
                viewModel.submissions.create(MemoEditorSubmissionId(701L), "same draft")
                advanceUntilIdle()
                createMemoUseCase.attemptedCreates.shouldHaveSize(2)
                createMemoUseCase.attemptedCreates.first() shouldBe createMemoUseCase.attemptedCreates.last()
                viewModel.submissionState.value shouldBe MemoEditorSubmissionState.Committed(MemoEditorSubmissionId(701L))
            }
        }

        test("createMemo forwards supplied backfill timestamp") {
            runTest {
                val viewModel = createViewModel()
                val timestampMillis = 1_777_777_777_000L

                viewModel.submissions.create(
                    submissionId = MemoEditorSubmissionId(2L),
                    content = "backfilled memo",
                    timestampMillis = timestampMillis,
                )
                advanceUntilIdle()

                createMemoUseCase.createMemoCalledWithContent shouldBe "backfilled memo"
                createMemoUseCase.createMemoCalledWithTimestamp shouldBe timestampMillis
            }
        }

        test("updateMemo failure maps to user-facing error") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo("memo-update")
                updateMemoContentUseCase.updateMemoException = IllegalStateException("update failed")

                val submissionId = MemoEditorSubmissionId(3L)
                viewModel.submissions.update(submissionId, memo, "updated")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "update failed"
                viewModel.submissionState.value shouldBe MemoEditorSubmissionState.Failed(submissionId)
            }
        }

        test("updateMemo success clears tracked images") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo("memo-update-success")
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-success"
                saveImageUseCase.customSaveResults["content://memo-editor/image-success"] =
                    SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-success.jpg"))

                viewModel.saveImage(uri, onResult = {}, onError = null)
                advanceUntilIdle()

                val submissionId = MemoEditorSubmissionId(4L)
                viewModel.submissions.update(submissionId, memo, "updated")
                advanceUntilIdle()
                viewModel.discardInputs()
                advanceUntilIdle()

                updateMemoContentUseCase.updateMemoCalledWithMemo shouldBe memo
                updateMemoContentUseCase.updateMemoCalledWithContent shouldBe "updated"
                viewModel.submissions.await(submissionId) shouldBe true
                discardDraftMediaUseCase.discardCalledWith shouldBe emptyList()
            }
        }

        test("saveImage success tracks saved path for later discard") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-track"
                saveImageUseCase.customSaveResults["content://memo-editor/image-track"] =
                    SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-track.jpg"))
                var savedPath: String? = null

                viewModel.saveImage(uri, onResult = { savedPath = it }, onError = null)
                advanceUntilIdle()
                viewModel.discardInputs()
                advanceUntilIdle()

                savedPath shouldBe "images/memo-editor-track.jpg"
                discardDraftMediaUseCase.discardCalledWith shouldBe listOf("images/memo-editor-track.jpg")
            }
        }

        test("trackVoiceMarkdown tracks destination for discard like images") {
            runTest {
                val viewModel = createViewModel()
                viewModel.trackVoiceMarkdown("![voice](media/voice_20260101_120000.m4a)")
                viewModel.discardInputs()
                advanceUntilIdle()

                discardDraftMediaUseCase.discardCalledWith shouldBe
                    listOf("media/voice_20260101_120000.m4a")
            }
        }

        test("extractMarkdownDestination strips angle brackets") {
            MemoEditorViewModel.extractMarkdownDestination("![voice](<media/voice.m4a>)") shouldBe
                "media/voice.m4a"
        }

        test("saveImage cache sync failure sets prefixed error and invokes onError") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-2"
                saveImageUseCase.saveException = IllegalStateException("cache failed")
                var savedPath: String? = null
                var onErrorCalled = false

                viewModel.saveImage(
                    uri = uri,
                    onResult = { path -> savedPath = path },
                    onError = { onErrorCalled = true },
                )
                advanceUntilIdle()

                savedPath shouldBe null
                viewModel.errorMessage.value shouldBe "Failed to save image: cache failed"
                onErrorCalled shouldBe true
            }
        }

        test("clearError clears existing error message") {
            runTest {
                createMemoUseCase.createMemoException = IllegalStateException("create failed")
                val viewModel = createViewModel()

                viewModel.submissions.create(MemoEditorSubmissionId(5L), "new memo")
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "create failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }

        test("discardInputs failure maps to prefixed error") {
            runTest {
                val viewModel = createViewModel()
                discardDraftMediaUseCase.discardException = IllegalStateException("discard failed")

                viewModel.discardInputs()
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to discard input: discard failed"
            }
        }

        test("given a submission that never acknowledges then a stalled diagnostic is recorded") {
            runTest {
                val viewModel = createViewModel()
                createMemoUseCase.createMemoGate = CompletableDeferred()

                viewModel.submissions.create(
                    submissionId = MemoEditorSubmissionId(1L),
                    content = "hanging memo",
                )
                advanceTimeBy(1_000L)
                diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Stalled>()
                    .shouldBeEmpty()

                advanceTimeBy(30_000L)

                val stalled = diagnostics.events.value.filterIsInstance<EngineDiagnosticEvent.Stalled>()
                stalled shouldHaveSize 1
                stalled[0].label shouldBe "editor.submit"
                viewModel.submissionState.value
                    .shouldBeInstanceOf<MemoEditorSubmissionState.Submitting>()

                createMemoUseCase.createMemoGate?.complete(Unit)
                advanceUntilIdle()
            }
        }
    }

    private class FakeEngineDiagnosticsRecorder : EngineDiagnosticsRecorder {
        private val _events = MutableStateFlow<List<EngineDiagnosticEvent>>(emptyList())
        override val events: StateFlow<List<EngineDiagnosticEvent>> = _events

        fun reset() {
            _events.value = emptyList()
        }

        override fun record(event: EngineDiagnosticEvent) {
            _events.value = listOf(event) + _events.value
        }
    }


    private fun createViewModel(): MemoEditorViewModel =
        MemoEditorViewModel(
            createMemoUseCase = createMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            discardDraftMediaUseCase = discardDraftMediaUseCase,
            loadCreateDraftUseCase = loadCreateDraftUseCase,
            saveCreateDraftUseCase = saveCreateDraftUseCase,
            diagnostics = diagnostics,
        )

    private fun sampleMemo(id: String): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = "memo content",
            rawContent = "- 10:00 memo content",
            dateKey = "2026_03_24",
            contentRevision = 1L,
            fileFingerprint = "editor-source-fingerprint",
        )

    class FakeCreateMemoUseCase : CreateMemoUseCase(mockk(), mockk(), mockk(), mockk()) {
        var createMemoCalledWithContent: String? = null
        var createMemoCalledWithTimestamp: Long? = null
        var createMemoException: Throwable? = null
        var createMemoGate: CompletableDeferred<Unit>? = null
        val attemptedCreates = mutableListOf<com.lomo.domain.model.MemoCreateAttempt>()

        fun reset() {
            attemptedCreates.clear()
            createMemoCalledWithContent = null
            createMemoCalledWithTimestamp = null
            createMemoException = null
            createMemoGate = null
        }

        override suspend fun invoke(attempt: com.lomo.domain.model.MemoCreateAttempt): Memo {
            val content = attempt.content
            val timestampMillis = attempt.timestampMillis
            attemptedCreates += attempt
            createMemoGate?.await()
            createMemoException?.let { throw it }
            createMemoCalledWithContent = content
            createMemoCalledWithTimestamp = timestampMillis
            return Memo(
                id = timestampMillis.toString(),
                timestamp = timestampMillis,
                content = content,
                rawContent = content,
                dateKey = "test",
            )
        }
    }

    class FakeUpdateMemoContentUseCase : UpdateMemoContentUseCase(mockk(), mockk()) {
        var updateMemoCalledWithMemo: Memo? = null
        var updateMemoCalledWithContent: String? = null
        var updateMemoException: Throwable? = null

        fun reset() {
            updateMemoCalledWithMemo = null
            updateMemoCalledWithContent = null
            updateMemoException = null
        }

        override suspend fun invoke(attempt: com.lomo.domain.model.MemoUpdateAttempt) {
            val memo = attempt.snapshot.memo
            val newContent = attempt.content
            updateMemoException?.let { throw it }
            updateMemoCalledWithMemo = memo
            updateMemoCalledWithContent = newContent
        }
    }

    class FakeSaveImageUseCase : SaveImageUseCase(mockk()) {
        val customSaveResults = mutableMapOf<String, SaveImageResult>()
        var saveException: Throwable? = null

        fun reset() {
            customSaveResults.clear()
            saveException = null
        }

        override suspend fun saveWithCacheSyncStatus(
            source: StorageLocation,
            draftId: com.lomo.domain.model.DraftId,
        ): SaveImageResult {
            saveException?.let { throw it }
            return customSaveResults[source.raw]
                ?: SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
        }
    }

    class FakeDiscardDraftMediaUseCase : DiscardDraftMediaUseCase(mockk()) {
        var discardCalledWith: Collection<String>? = null
        var discardException: Throwable? = null

        fun reset() {
            discardCalledWith = null
            discardException = null
        }

        override suspend fun invoke(
            filenames: Collection<String>,
            draftId: com.lomo.domain.model.DraftId,
        ) {
            discardException?.let { throw it }
            discardCalledWith = filenames
        }
    }

    class FakeLoadCreateDraftUseCase(
        private val stored: MutableStateFlow<String?>,
    ) : LoadCreateDraftUseCase(mockk()) {
        var loadGate: CompletableDeferred<Unit>? = null

        fun reset() {
            loadGate = null
        }

        override suspend operator fun invoke(): MemoCreateDraft? {
            loadGate?.await()
            return stored.value?.let { MemoCreateDraft(it) }
        }
    }

    class FakeSaveCreateDraftUseCase(
        private val stored: MutableStateFlow<String?>,
    ) : SaveCreateDraftUseCase(mockk()) {
        var saveCalledCount = 0
        var saveCalledWithValue: String? = null

        fun reset() {
            saveCalledCount = 0
            saveCalledWithValue = null
        }

        override suspend operator fun invoke(content: String?) {
            saveCalledCount++
            saveCalledWithValue = content
            stored.value = content
        }
    }
}
