package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.feature.media.MemoImageWorkflow
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.validation.MemoContentValidator
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
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var memoFlowProcessor: MemoFlowProcessor
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var audioPlayerManager: com.lomo.ui.media.AudioPlayerManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        savedStateHandle = SavedStateHandle()
        repository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        mediaRepository = mockk(relaxed = true)
        dataStore = mockk(relaxed = true)
        appWidgetRepository = mockk(relaxed = true)
        memoFlowProcessor = MemoFlowProcessor(MemoUiMapper())
        imageMapProvider = mockk(relaxed = true)
        audioPlayerManager = mockk(relaxed = true)

        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())
        every { repository.isSyncing() } returns flowOf(false)
        every { repository.getAllMemosList() } returns flowOf(emptyList<Memo>())
        every { repository.searchMemosList(any()) } returns flowOf(emptyList<Memo>())
        every { repository.getMemosByTagList(any()) } returns flowOf(emptyList<Memo>())
        every { repository.getActiveDayCount() } returns flowOf(0)
        every { settingsRepository.getRootDirectory() } returns flowOf<String?>(null)
        coEvery { settingsRepository.getRootDirectoryOnce() } returns null
        every { settingsRepository.getImageDirectory() } returns flowOf<String?>(null)
        every { settingsRepository.getVoiceDirectory() } returns flowOf<String?>(null)

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
            memoFlowProcessor = memoFlowProcessor,
            imageMapProvider = imageMapProvider,
            memoContentValidator = MemoContentValidator(),
            mainMediaCoordinator = MainMediaCoordinator(mediaRepository, MemoImageWorkflow(mediaRepository)),
            appWidgetRepository = appWidgetRepository,
            textProcessor = MemoTextProcessor(),
            startupCoordinator =
                MainStartupCoordinator(
                    repository = repository,
                    mediaRepository = mediaRepository,
                    settingsRepository = settingsRepository,
                    dataStore = dataStore,
                    audioPlayerManager = audioPlayerManager,
                ),
        )
}
