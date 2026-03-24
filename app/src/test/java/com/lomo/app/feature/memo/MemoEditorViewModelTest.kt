package com.lomo.app.feature.memo

import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEditorViewModel
 * - Behavior focus: draft persistence state machine, success-path cleanup side effects, and failure message mapping.
 * - Observable outcomes: draftText/errorMessage state, callback invocation, and use-case invocation payloads.
 * - Excludes: widget update internals, media repository implementation details, and Compose rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var createMemoUseCase: CreateMemoUseCase
    private lateinit var updateMemoContentUseCase: UpdateMemoContentUseCase
    private lateinit var saveImageUseCase: SaveImageUseCase
    private lateinit var discardMemoDraftAttachmentsUseCase: DiscardMemoDraftAttachmentsUseCase
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var observeDraftTextUseCase: ObserveDraftTextUseCase
    private lateinit var setDraftTextUseCase: SetDraftTextUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

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

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveDraft updates local draft state and persists text`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.saveDraft("draft A")
            advanceUntilIdle()

            assertEquals("draft A", viewModel.draftText.value)
            coVerify(exactly = 1) { setDraftTextUseCase("draft A") }
        }

    @Test
    fun `clearDraft clears local state and persists null`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.clearDraft()
            advanceUntilIdle()

            assertEquals("", viewModel.draftText.value)
            coVerify(exactly = 1) { setDraftTextUseCase(null) }
        }

    @Test
    fun `createMemo success clears tracked images and draft`() =
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

            assertEquals(true, successCalled)
            assertEquals("", viewModel.draftText.value)
            coVerify(exactly = 1) { createMemoUseCase("new memo", any()) }
            coVerify(atLeast = 1) { setDraftTextUseCase(null) }
            coVerify(exactly = 1) { discardMemoDraftAttachmentsUseCase(match { it.isEmpty() }) }
        }

    @Test
    fun `createMemo failure surfaces throwable message and preserves draft`() =
        runTest {
            coEvery { createMemoUseCase(any(), any()) } throws IllegalStateException("create failed")
            val viewModel = createViewModel()
            viewModel.saveDraft("keep me")
            advanceUntilIdle()

            viewModel.createMemo("new memo")
            advanceUntilIdle()

            assertEquals("create failed", viewModel.errorMessage.value)
            assertEquals("keep me", viewModel.draftText.value)
        }

    @Test
    fun `updateMemo failure maps to user-facing error`() =
        runTest {
            val viewModel = createViewModel()
            val memo = sampleMemo("memo-update")
            coEvery { updateMemoContentUseCase(memo, "updated") } throws IllegalStateException("update failed")

            viewModel.updateMemo(memo, "updated")
            advanceUntilIdle()

            assertEquals("update failed", viewModel.errorMessage.value)
        }

    @Test
    fun `updateMemo success clears tracked images and updates widgets`() =
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

    @Test
    fun `saveImage success tracks saved path for later discard`() =
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

            assertEquals("images/memo-editor-track.jpg", savedPath)
            coVerify(exactly = 1) {
                discardMemoDraftAttachmentsUseCase(listOf("images/memo-editor-track.jpg"))
            }
        }

    @Test
    fun `saveImage cache sync failure sets prefixed error and invokes onError`() =
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

            assertNull(savedPath)
            assertEquals("Failed to save image: cache failed", viewModel.errorMessage.value)
            assertEquals(true, onErrorCalled)
        }

    @Test
    fun `clearError clears existing error message`() =
        runTest {
            coEvery { createMemoUseCase(any(), any()) } throws IllegalStateException("create failed")
            val viewModel = createViewModel()

            viewModel.createMemo("new memo")
            advanceUntilIdle()
            assertEquals("create failed", viewModel.errorMessage.value)

            viewModel.clearError()

            assertNull(viewModel.errorMessage.value)
        }

    @Test
    fun `discardInputs failure maps to prefixed error`() =
        runTest {
            val viewModel = createViewModel()
            coEvery { discardMemoDraftAttachmentsUseCase(any()) } throws IllegalStateException("discard failed")

            viewModel.discardInputs()
            advanceUntilIdle()

            assertEquals("Failed to discard input: discard failed", viewModel.errorMessage.value)
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
