package com.lomo.app.feature.memo

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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: MemoEditorViewModel
 * - Behavior focus: draft persistence state machine, success-path cleanup side effects, optional backfill timestamp forwarding, and failure message mapping.
 * - Observable outcomes: draftText/errorMessage state, callback invocation, and use-case invocation payloads including timestamp.
 * - Red phase: Fails before the fix because ViewModel construction blocks until the first draft-text emission arrives.
 * - Excludes: widget update internals, media repository implementation details, and Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))
    }

    private lateinit var createMemoUseCase: CreateMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase
    private lateinit var discardMemoDraftAttachmentsUseCase: DiscardMemoDraftAttachmentsUseCase
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var observeDraftTextUseCase: ObserveDraftTextUseCase
    private lateinit var setDraftTextUseCase: SetDraftTextUseCase

    init {
        beforeTest {
createMemoUseCase = mockk(relaxed = true)
            updateMemoContentUseCase = mockk(relaxed = true)
            saveImageUseCase = mockk(relaxed = true)
            discardMemoDraftAttachmentsUseCase = mockk(relaxed = true)
            appWidgetRepository = mockk(relaxed = true)
            observeDraftTextUseCase = mockk(relaxed = true)
            setDraftTextUseCase = mockk(relaxed = true)

            every { observeDraftTextUseCase() } returns flowOf("initial draft")
            coEvery { createMemoUseCase(any(), any()) } returns Unit
            coEvery { updateMemoContentUseCase(any(), any()) } returns Unit
            coEvery { setDraftTextUseCase(any()) } returns Unit
            coEvery { discardMemoDraftAttachmentsUseCase(any()) } returns Unit
            coEvery { appWidgetRepository.updateAllWidgets() } returns Unit
            coEvery { saveImageUseCase.saveWithCacheSyncStatus(any()) } returns
                SaveImageResult.SavedAndCacheSynced(StorageLocation("images/default.jpg"))
        }
    }

    init {
        test("saveDraft updates local draft state and persists text") {
            runTest {
                val viewModel = createViewModel()

                viewModel.saveDraft("draft A")
                advanceUntilIdle()

                (viewModel.draftText.value) shouldBe ("draft A")
                coVerify(exactly = 1) { setDraftTextUseCase("draft A") }
            }
        }
    }

    init {
        test("clearDraft clears local state and persists null") {
            runTest {
                val viewModel = createViewModel()

                viewModel.clearDraft()
                advanceUntilIdle()

                (viewModel.draftText.value) shouldBe ("")
                coVerify(exactly = 1) { setDraftTextUseCase(null) }
            }
        }
    }

    init {
        test("constructor does not wait for first persisted draft emission") {
            runTest {
                val firstDraftGate = CompletableDeferred<Unit>()
                every { observeDraftTextUseCase() } returns kotlinx.coroutines.flow.flow {
                    firstDraftGate.await()
                    emit("loaded draft")
                }
                val executor = Executors.newSingleThreadExecutor()

                try {
                    val future = executor.submit<MemoEditorViewModel> { createViewModel() }
                    val viewModel = future.get(200, TimeUnit.MILLISECONDS)

                    (viewModel.draftText.value) shouldBe ("")

                    firstDraftGate.complete(Unit)
                    advanceUntilIdle()

                    (viewModel.draftText.value) shouldBe ("loaded draft")
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    init {
        test("createMemo success clears tracked images and draft") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-1"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://memo-editor/image-1"))
                } returns SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-1.jpg"))

                viewModel.saveDraft("to be cleared")
                viewModel.saveImage(uri, onResult = {}, onError = null)
                advanceUntilIdle()

                var successCalled = false
                viewModel.createMemo(content = "new memo", onSuccess = { successCalled = true })
                advanceUntilIdle()

                viewModel.discardInputs()
                advanceUntilIdle()

                (successCalled) shouldBe (true)
                (viewModel.draftText.value) shouldBe ("")
                coVerify(exactly = 1) { createMemoUseCase("new memo", any()) }
                coVerify(atLeast = 1) { setDraftTextUseCase(null) }
                coVerify(exactly = 1) { discardMemoDraftAttachmentsUseCase(match { it.isEmpty() }) }
            }
        }
    }

    init {
        test("createMemo failure surfaces throwable message and preserves draft") {
            runTest {
                coEvery { createMemoUseCase(any(), any()) } throws IllegalStateException("create failed")
                val viewModel = createViewModel()
                viewModel.saveDraft("keep me")
                advanceUntilIdle()

                viewModel.createMemo("new memo")
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("create failed")
                (viewModel.draftText.value) shouldBe ("keep me")
            }
        }
    }

    init {
        test("createMemo forwards supplied backfill timestamp") {
            runTest {
                val viewModel = createViewModel()
                val timestampMillis = 1_777_777_777_000L

                viewModel.createMemo(
                    content = "backfilled memo",
                    timestampMillis = timestampMillis,
                )
                advanceUntilIdle()

                coVerify(exactly = 1) {
                    createMemoUseCase(
                        content = "backfilled memo",
                        timestampMillis = timestampMillis,
                        geoLocation = null,
                    )
                }
            }
        }
    }

    init {
        test("updateMemo failure maps to user-facing error") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo("memo-update")
                coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("update failed")
            }
        }
    }

    init {
        test("updateMemo success clears tracked images and updates widgets") {
            runTest {
                val viewModel = createViewModel()
                val memo = sampleMemo("memo-update-success")
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-success"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://memo-editor/image-success"))
                } returns SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-success.jpg"))

                viewModel.saveImage(uri, onResult = {}, onError = null)
                advanceUntilIdle()

                viewModel.updateMemo(memo, "updated")
                advanceUntilIdle()
                viewModel.discardInputs()
                advanceUntilIdle()

                coVerify(exactly = 1) { updateMemoContentUseCase(memo, "updated") }
                coVerify(exactly = 1) { appWidgetRepository.updateAllWidgets() }
                coVerify(exactly = 1) { discardMemoDraftAttachmentsUseCase(match { it.isEmpty() }) }
            }
        }
    }

    init {
        test("saveImage success tracks saved path for later discard") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-track"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://memo-editor/image-track"))
                } returns SaveImageResult.SavedAndCacheSynced(StorageLocation("images/memo-editor-track.jpg"))
                var savedPath: String? = null

                viewModel.saveImage(uri, onResult = { savedPath = it }, onError = null)
                advanceUntilIdle()
                viewModel.discardInputs()
                advanceUntilIdle()

                (savedPath) shouldBe ("images/memo-editor-track.jpg")
                coVerify(exactly = 1) {
                    discardMemoDraftAttachmentsUseCase(listOf("images/memo-editor-track.jpg"))
                }
            }
        }
    }

    init {
        test("saveImage cache sync failure sets prefixed error and invokes onError") {
            runTest {
                val viewModel = createViewModel()
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "content://memo-editor/image-2"
                coEvery {
                    saveImageUseCase.saveWithCacheSyncStatus(StorageLocation("content://memo-editor/image-2"))
                } returns
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

                (savedPath) shouldBe null
                (viewModel.errorMessage.value) shouldBe ("Failed to save image: cache failed")
                (onErrorCalled) shouldBe (true)
            }
        }
    }

    init {
        test("clearError clears existing error message") {
            runTest {
                coEvery { createMemoUseCase(any(), any()) } throws IllegalStateException("create failed")
                val viewModel = createViewModel()

                viewModel.createMemo("new memo")
                advanceUntilIdle()
                (viewModel.errorMessage.value) shouldBe ("create failed")

                viewModel.clearError()

                (viewModel.errorMessage.value) shouldBe null
            }
        }
    }

    init {
        test("discardInputs failure maps to prefixed error") {
            runTest {
                val viewModel = createViewModel()
                coEvery { discardMemoDraftAttachmentsUseCase(any()) } throws IllegalStateException("discard failed")

                viewModel.discardInputs()
                advanceUntilIdle()

                (viewModel.errorMessage.value) shouldBe ("Failed to discard input: discard failed")
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
}
