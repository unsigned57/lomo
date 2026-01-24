package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MainViewModel.
 * Tests search state management and tag selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var mapper: MemoUiMapper
    private lateinit var textProcessor: com.lomo.data.util.MemoTextProcessor
    private lateinit var getFilteredMemosUseCase: com.lomo.domain.usecase.GetFilteredMemosUseCase
    private lateinit var imageMapProvider: com.lomo.domain.provider.ImageMapProvider
    private lateinit var appContext: android.content.Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        savedStateHandle = SavedStateHandle()
        repository = mockk(relaxed = true)
        mapper = mockk(relaxed = true)
        textProcessor = mockk(relaxed = true)
        getFilteredMemosUseCase = mockk(relaxed = true)
        imageMapProvider = mockk(relaxed = true)
        appContext = mockk(relaxed = true)

        // Default stubs
        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())
        every { repository.getRootDirectory() } returns flowOf<String?>(null)
        every { repository.getImageDirectory() } returns flowOf<String?>(null)
        every { repository.isSyncing() } returns flowOf(false)
        every { repository.getImageUriMap() } returns flowOf<Map<String, String>>(emptyMap())
        every { repository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { repository.getTimeFormat() } returns flowOf("HH:mm")
        every { repository.isHapticFeedbackEnabled() } returns flowOf(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial search query is empty`() =
        runTest {
            val viewModel = createViewModel()
            assertEquals("", viewModel.searchQuery.value)
        }

    @Test
    fun `onSearch updates searchQuery`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSearch("test query")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("test query", viewModel.searchQuery.value)
        }

    @Test
    fun `initial selected tag is null`() =
        runTest {
            val viewModel = createViewModel()
            assertNull(viewModel.selectedTag.value)
        }

    @Test
    fun `onTagSelected sets tag`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onTagSelected("work")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("work", viewModel.selectedTag.value)
        }

    @Test
    fun `onTagSelected toggles same tag to null`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onTagSelected("work")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onTagSelected("work")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.selectedTag.value)
        }

    @Test
    fun `clearFilters resets search and tag`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSearch("query")
            viewModel.onTagSelected("tag")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.clearFilters()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("", viewModel.searchQuery.value)
            assertNull(viewModel.selectedTag.value)
        }

    private fun createViewModel(): MainViewModel =
        MainViewModel(
            savedStateHandle = savedStateHandle,
            repository = repository,
            mapper = mapper,
            textProcessor = textProcessor,
            getFilteredMemosUseCase = getFilteredMemosUseCase,
            imageMapProvider = imageMapProvider,
            appContext = appContext,
        )
}
