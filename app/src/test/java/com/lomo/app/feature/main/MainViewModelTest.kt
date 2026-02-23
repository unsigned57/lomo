package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.feature.media.MemoImageWorkflow
import com.lomo.app.provider.ImageMapProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var repository: com.lomo.domain.repository.MemoRepository
    private lateinit var settingsRepository: com.lomo.domain.repository.SettingsRepository
    private lateinit var mediaRepository: com.lomo.domain.repository.MediaRepository
    private lateinit var dataStore: com.lomo.data.local.datastore.LomoDataStore
    private lateinit var mapper: MemoUiMapper
    private lateinit var memoMutator: MainMemoMutator
    private lateinit var getFilteredMemosUseCase: com.lomo.domain.usecase.GetFilteredMemosUseCase
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var audioPlayerManager: com.lomo.ui.media.AudioPlayerManager
    private lateinit var updateManager: com.lomo.app.feature.update.UpdateManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        savedStateHandle = SavedStateHandle()
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        mediaRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        mapper = MemoUiMapper()
        memoMutator = mockk(relaxed = true)
        getFilteredMemosUseCase = mockk(relaxed = true)
        imageMapProvider = mockk(relaxed = true)
        audioPlayerManager = mockk(relaxed = true)
        updateManager = mockk(relaxed = true)

        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())
        every { repository.getRootDirectory() } returns flowOf<String?>(null)
        coEvery { repository.getRootDirectoryOnce() } returns null
        every { repository.getImageDirectory() } returns flowOf<String?>(null)
        every { repository.getVoiceDirectory() } returns flowOf<String?>(null)
        every { repository.isSyncing() } returns flowOf(false)
        every { repository.getActiveDayCount() } returns flowOf(0)

        every { settingsRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { settingsRepository.getTimeFormat() } returns flowOf("HH:mm")
        every { settingsRepository.isHapticFeedbackEnabled() } returns flowOf(true)
        every { settingsRepository.isShowInputHintsEnabled() } returns flowOf(true)
        every { settingsRepository.isDoubleTapEditEnabled() } returns flowOf(true)
        every { settingsRepository.getShareCardStyle() } returns flowOf("default")
        every { settingsRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { settingsRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
        every { settingsRepository.getThemeMode() } returns flowOf("system")
        every { settingsRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)

        coEvery { dataStore.getLastAppVersionOnce() } returns ""
        coEvery { dataStore.updateLastAppVersion(any()) } returns Unit

        every { getFilteredMemosUseCase.invoke(any(), any()) } returns flowOf(emptyList())
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
            repository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle,
            mapper = mapper,
            imageMapProvider = imageMapProvider,
            getFilteredMemosUseCase = getFilteredMemosUseCase,
            memoMutator = memoMutator,
            startupCoordinator =
                MainStartupCoordinator(
                    repository = repository,
                    mediaRepository = mediaRepository,
                    settingsRepository = settingsRepository,
                    dataStore = dataStore,
                    audioPlayerManager = audioPlayerManager,
                    updateManager = updateManager,
                ),
            mediaCoordinator = MainMediaCoordinator(mediaRepository, MemoImageWorkflow(mediaRepository)),
        )
}
