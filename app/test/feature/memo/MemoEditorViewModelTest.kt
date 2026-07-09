package com.lomo.app.feature.memo

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


import com.lomo.app.repository.AppWidgetRepository
import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Draft persistence state machine, success-path cleanup side effects, optional backfill timestamp forwarding, and failure message mapping.
 * - Scenarios:
 *   - Given starting a memo draft, save/clear persists state to storage.
 *   - Given constructor is called, it does not block on first persisted draft emission.
 *   - Given createMemo/updateMemo success, discard inputs, clear draft text, and update widgets.
 *   - Given saveImage success or failure, manage tracked image list and propagate error states appropriately.
 * - Observable outcomes:
 *   - draftText/errorMessage state, callbacks, and use-case invocation payloads.
 * - TDD proof: Asserts correct draft persistence flow and exception mapping in the memo editor.
 * - Excludes: actual widgets UI layout and Compose rendering components.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val sharedDraftTextFlow = MutableStateFlow("initial draft")

    private val createMemoUseCase = FakeCreateMemoUseCase()
    private val updateMemoContentUseCase = FakeUpdateMemoContentUseCase()
    private val saveImageUseCase = FakeSaveImageUseCase()
    private val discardMemoDraftAttachmentsUseCase = FakeDiscardMemoDraftAttachmentsUseCase()
    private val appWidgetRepository = FakeAppWidgetRepository()
    private val observeDraftTextUseCase = FakeObserveDraftTextUseCase(sharedDraftTextFlow)
    private val setDraftTextUseCase = FakeSetDraftTextUseCase(sharedDraftTextFlow)

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            sharedDraftTextFlow.value = "initial draft"
            createMemoUseCase.reset()
            updateMemoContentUseCase.reset()
            saveImageUseCase.reset()
            discardMemoDraftAttachmentsUseCase.reset()
            appWidgetRepository.reset()
            observeDraftTextUseCase.reset()
            setDraftTextUseCase.reset()
        }

        test("saveDraft updates local draft state and persists text") {
            runTest {
                val viewModel = createViewModel()

                viewModel.saveDraft("draft A")
                advanceUntilIdle()

                viewModel.draftText.value shouldBe "draft A"
                setDraftTextUseCase.setDraftTextCalledWithValue shouldBe "draft A"
                setDraftTextUseCase.setDraftTextCalledCount shouldBe 1
            }
        }

        test("clearDraft clears local state and persists null") {
            runTest {
                val viewModel = createViewModel()

                viewModel.clearDraft()
                advanceUntilIdle()

                viewModel.draftText.value shouldBe ""
                setDraftTextUseCase.setDraftTextCalledWithValue shouldBe null
                setDraftTextUseCase.setDraftTextCalledCount shouldBe 1
            }
        }

        test("constructor does not wait for first persisted draft emission") {
            runTest {
                val firstDraftGate = CompletableDeferred<Unit>()
                observeDraftTextUseCase.customFlow = kotlinx.coroutines.flow.flow {
                    firstDraftGate.await()
                    emit("loaded draft")
                }
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

                var successCalled = false
                viewModel.createMemo(content = "new memo", onSuccess = { successCalled = true })
                advanceUntilIdle()

                viewModel.discardInputs()
                advanceUntilIdle()

                successCalled shouldBe true
                viewModel.draftText.value shouldBe ""
                createMemoUseCase.createMemoCalledWithContent shouldBe "new memo"
                setDraftTextUseCase.setDraftTextCalledWithValue shouldBe null
                discardMemoDraftAttachmentsUseCase.discardCalledWith shouldBe emptyList()
            }
        }

        test("createMemo failure surfaces throwable message and preserves draft") {
            runTest {
                createMemoUseCase.createMemoException = IllegalStateException("create failed")
                val viewModel = createViewModel()
                viewModel.saveDraft("keep me")
                advanceUntilIdle()

                viewModel.createMemo("new memo")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "create failed"
                viewModel.draftText.value shouldBe "keep me"
            }
        }

        test("createMemo forwards supplied backfill timestamp") {
            runTest {
                val viewModel = createViewModel()
                val timestampMillis = 1_777_777_777_000L

                viewModel.createMemo(
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

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "update failed"
            }
        }

        test("updateMemo success clears tracked images and updates widgets") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo("memo-update-success")
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-success"
                saveImageUseCase.customSaveResults["content://memo-editor/image-success"] =
                    SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-success.jpg"))

                viewModel.saveImage(uri, onResult = {}, onError = null)
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()
                viewModel.discardInputs()
                advanceUntilIdle()

                updateMemoContentUseCase.updateMemoCalledWithMemo shouldBe memo
                updateMemoContentUseCase.updateMemoCalledWithContent shouldBe "updated"
                appWidgetRepository.updateAllWidgetsCalledCount shouldBe 1
                discardMemoDraftAttachmentsUseCase.discardCalledWith shouldBe emptyList()
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
                discardMemoDraftAttachmentsUseCase.discardCalledWith shouldBe listOf("images/memo-editor-track.jpg")
            }
        }

        test("saveImage cache sync failure sets prefixed error and invokes onError") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-2"
                saveImageUseCase.customSaveResults["content://memo-editor/image-2"] =
                    SaveImageResult.SavedButCacheSyncFailed(
                        location = StorageLocation("images/memo-editor-2.jpg"),
                        cause = IllegalStateException("cache failed"),
                    )
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

                viewModel.createMemo("new memo")
                advanceUntilIdle()
                viewModel.errorMessage.value shouldBe "create failed"

                viewModel.clearError()

                viewModel.errorMessage.value shouldBe null
            }
        }

        test("discardInputs failure maps to prefixed error") {
            runTest {
                val viewModel = createViewModel()
                discardMemoDraftAttachmentsUseCase.discardException = IllegalStateException("discard failed")

                viewModel.discardInputs()
                advanceUntilIdle()

                viewModel.errorMessage.value shouldBe "Failed to discard input: discard failed"
            }
        }
    }

    private fun createViewModel(): MemoEditorViewModel =
        MemoEditorViewModel(
            createMemoUseCase = createMemoUseCase,
            updateMemoContentUseCase = updateMemoContentUseCase,
            saveImageUseCase = saveImageUseCase,
            discardMemoDraftAttachmentsUseCase = discardMemoDraftAttachmentsUseCase,
            appWidgetRepository = appWidgetRepository,
            observeDraftTextUseCase = observeDraftTextUseCase,
            setDraftTextUseCase = setDraftTextUseCase,
        )

    private fun sampleMemo(id: String): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            content = "memo content",
            rawContent = "- 10:00 memo content",
            dateKey = "2026_03_24",
        )

    class FakeCreateMemoUseCase : CreateMemoUseCase(mockk(), mockk(), mockk()) {
        var createMemoCalledWithContent: String? = null
        var createMemoCalledWithTimestamp: Long? = null
        var createMemoException: Throwable? = null

        fun reset() {
            createMemoCalledWithContent = null
            createMemoCalledWithTimestamp = null
            createMemoException = null
        }

        override suspend fun invoke(content: String, timestampMillis: Long, geoLocation: String?): Memo {
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

    class FakeUpdateMemoContentUseCase : UpdateMemoContentUseCase(mockk(), mockk(), mockk(), mockk()) {
        var updateMemoCalledWithMemo: Memo? = null
        var updateMemoCalledWithContent: String? = null
        var updateMemoException: Throwable? = null

        fun reset() {
            updateMemoCalledWithMemo = null
            updateMemoCalledWithContent = null
            updateMemoException = null
        }

        override suspend fun invoke(memo: Memo, newContent: String) {
            updateMemoException?.let { throw it }
            updateMemoCalledWithMemo = memo
            updateMemoCalledWithContent = newContent
        }
    }

    class FakeSaveImageUseCase : SaveImageUseCase(mockk()) {
        val customSaveResults = mutableMapOf<String, SaveImageResult>()

        fun reset() {
            customSaveResults.clear()
        }

        override suspend fun saveWithCacheSyncStatus(source: StorageLocation): SaveImageResult {
            return customSaveResults[source.raw]
                ?: SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
        }
    }

    class FakeDiscardMemoDraftAttachmentsUseCase : DiscardMemoDraftAttachmentsUseCase(mockk()) {
        var discardCalledWith: Collection<String>? = null
        var discardException: Throwable? = null

        fun reset() {
            discardCalledWith = null
            discardException = null
        }

        override suspend fun invoke(filenames: Collection<String>) {
            discardException?.let { throw it }
            discardCalledWith = filenames
        }
    }

    class FakeObserveDraftTextUseCase(
        private val sharedDraftTextFlow: MutableStateFlow<String>,
    ) : ObserveDraftTextUseCase(mockk()) {
        var customFlow: Flow<String>? = null

        fun reset() {
            customFlow = null
        }

        override fun invoke(): Flow<String> = customFlow ?: sharedDraftTextFlow
    }

    class FakeSetDraftTextUseCase(
        private val sharedDraftTextFlow: MutableStateFlow<String>,
    ) : SetDraftTextUseCase(mockk()) {
        var setDraftTextCalledCount = 0
        var setDraftTextCalledWithValue: String? = null

        fun reset() {
            setDraftTextCalledCount = 0
            setDraftTextCalledWithValue = null
        }

        override suspend fun invoke(text: String?) {
            setDraftTextCalledCount++
            setDraftTextCalledWithValue = text
            sharedDraftTextFlow.value = text ?: ""
        }
    }

    class FakeAppWidgetRepository : AppWidgetRepository(mockk()) {
        var updateAllWidgetsCalledCount = 0

        fun reset() {
            updateAllWidgetsCalledCount = 0
        }

        override suspend fun updateAllWidgets() {
            updateAllWidgetsCalledCount++
        }
    }
}
