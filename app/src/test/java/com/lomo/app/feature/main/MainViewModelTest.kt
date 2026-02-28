package com.lomo.app.feature.main

import androidx.lifecycle.SavedStateHandle
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.provider.ImageMapProvider
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.RefreshMemosUseCase
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase
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
    private lateinit var directorySettings: com.lomo.domain.repository.DirectorySettingsRepository
    private lateinit var preferencesRepository: com.lomo.domain.repository.PreferencesRepository
    private lateinit var gitSyncRepo: com.lomo.domain.repository.GitSyncRepository
    private lateinit var mediaRepository: com.lomo.domain.repository.MediaRepository
    private lateinit var appVersionRepository: com.lomo.domain.repository.AppVersionRepository
    private lateinit var appWidgetRepository: AppWidgetRepository
    private lateinit var memoFlowProcessor: MemoFlowProcessor
    private lateinit var imageMapProvider: ImageMapProvider
    private lateinit var audioPlayerManager: com.lomo.ui.media.AudioPlayerManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        savedStateHandle = SavedStateHandle()
        repository = mockk(relaxed = true)
        directorySettings = mockk(relaxed = true)
        preferencesRepository = mockk(relaxed = true)
        gitSyncRepo = mockk(relaxed = true)
        mediaRepository = mockk(relaxed = true)
        appVersionRepository = mockk(relaxed = true)
        appWidgetRepository = mockk(relaxed = true)
        memoFlowProcessor = MemoFlowProcessor(MemoUiMapper())
        imageMapProvider = mockk(relaxed = true)
        audioPlayerManager = mockk(relaxed = true)

        every { imageMapProvider.imageMap } returns MutableStateFlow(emptyMap())
        every { repository.isSyncing() } returns flowOf(false)
        every { repository.getAllMemosList() } returns flowOf(emptyList<Memo>())
        every { repository.searchMemosList(any()) } returns flowOf(emptyList<Memo>())
        every { repository.getMemosByTagList(any()) } returns flowOf(emptyList<Memo>())
        every { repository.getMemoCountFlow() } returns flowOf(0)
        every { repository.getMemoTimestampsFlow() } returns flowOf(emptyList())
        every { repository.getMemoCountByDateFlow() } returns flowOf(emptyMap())
        every { repository.getTagCountsFlow() } returns flowOf(emptyList<com.lomo.domain.model.MemoTagCount>())
        every { repository.getActiveDayCount() } returns flowOf(0)
        every { gitSyncRepo.isGitSyncEnabled() } returns flowOf(false)
        every { gitSyncRepo.getSyncOnRefreshEnabled() } returns flowOf(false)
        every { directorySettings.getRootDirectory() } returns flowOf<String?>(null)
        coEvery { directorySettings.getRootDirectoryOnce() } returns null
        every { directorySettings.getImageDirectory() } returns flowOf<String?>(null)
        every { directorySettings.getVoiceDirectory() } returns flowOf<String?>(null)

        every { preferencesRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { preferencesRepository.getTimeFormat() } returns flowOf("HH:mm")
        every { preferencesRepository.isHapticFeedbackEnabled() } returns flowOf(true)
        every { preferencesRepository.isShowInputHintsEnabled() } returns flowOf(true)
        every { preferencesRepository.isDoubleTapEditEnabled() } returns flowOf(true)
        every { preferencesRepository.getShareCardStyle() } returns flowOf(ShareCardStyle.CLEAN)
        every { preferencesRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { preferencesRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
        every { preferencesRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { preferencesRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)

        coEvery { appVersionRepository.getLastAppVersionOnce() } returns ""
        coEvery { appVersionRepository.updateLastAppVersion(any()) } returns Unit
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
            settingsRepository = directorySettings,
            preferencesRepository = preferencesRepository,
            gitSyncRepo = gitSyncRepo,
            savedStateHandle = savedStateHandle,
            memoFlowProcessor = memoFlowProcessor,
            imageMapProvider = imageMapProvider,
            mainMemoMutationUseCase =
                MainMemoMutationUseCase(
                    deleteMemoUseCase = DeleteMemoUseCase(repository),
                    toggleMemoCheckboxUseCase = ToggleMemoCheckboxUseCase(repository, MemoContentValidator()),
                    appWidgetRepository = appWidgetRepository,
                ),
            refreshMemosUseCase = RefreshMemosUseCase(repository, gitSyncRepo),
            mainMediaCoordinator = MainMediaCoordinator(mediaRepository),
            startupCoordinator =
                MainStartupCoordinator(
                    repository = repository,
                    mediaRepository = mediaRepository,
                    settingsRepository = directorySettings,
                    appVersionRepository = appVersionRepository,
                    audioPlayerManager = audioPlayerManager,
                ),
        )
}
