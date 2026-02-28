package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.SyncSchedulerRepository
import com.lomo.domain.usecase.SyncAndRebuildUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var shareServiceManager: LanShareService
    private lateinit var gitSyncRepo: GitSyncRepository
    private lateinit var syncSchedulerRepository: SyncSchedulerRepository
    private lateinit var syncAndRebuildUseCase: SyncAndRebuildUseCase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        appConfigRepository = mockk(relaxed = true)
        shareServiceManager = mockk(relaxed = true)
        gitSyncRepo = mockk(relaxed = true)
        syncSchedulerRepository = mockk(relaxed = true)
        syncAndRebuildUseCase = mockk(relaxed = true)

        every { appConfigRepository.getRootDisplayName() } returns flowOf("")
        every { appConfigRepository.getImageDisplayName() } returns flowOf("")
        every { appConfigRepository.getVoiceDisplayName() } returns flowOf("")
        every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
        every { appConfigRepository.getStorageFilenameFormat() } returns flowOf("default")
        every { appConfigRepository.getStorageTimestampFormat() } returns flowOf("HHmm")
        every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)
        every { appConfigRepository.getShareCardStyle() } returns flowOf(ShareCardStyle.CLEAN)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)

        every { shareServiceManager.lanShareE2eEnabled } returns flowOf(true)
        every { shareServiceManager.lanSharePairingConfigured } returns flowOf(false)
        every { shareServiceManager.lanShareDeviceName } returns flowOf("Local")

        every { gitSyncRepo.isGitSyncEnabled() } returns flowOf(false)
        every { gitSyncRepo.getRemoteUrl() } returns flowOf("")
        every { gitSyncRepo.getAuthorName() } returns flowOf("Lomo")
        every { gitSyncRepo.getAuthorEmail() } returns flowOf("lomo@example.com")
        every { gitSyncRepo.getAutoSyncEnabled() } returns flowOf(false)
        every { gitSyncRepo.getAutoSyncInterval() } returns flowOf("15m")
        every { gitSyncRepo.getSyncOnRefreshEnabled() } returns flowOf(false)
        every { gitSyncRepo.getLastSyncTime() } returns flowOf(0L)
        every { gitSyncRepo.syncState() } returns flowOf(SyncEngineState.Idle)

        coEvery { gitSyncRepo.getToken() } returns null
        coEvery { gitSyncRepo.testConnection() } returns GitSyncResult.Success("ok")
        coEvery { gitSyncRepo.resetRepository() } returns GitSyncResult.Success("ok")
        coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `triggerGitSyncNow exposes operationError on failure`() =
        runTest {
            coEvery { syncAndRebuildUseCase.invoke(forceSync = true) } throws IllegalStateException("sync failed")
            val viewModel = createViewModel()

            viewModel.triggerGitSyncNow()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("sync failed", viewModel.operationError.value)
        }

    @Test
    fun `testGitConnection exposes error state and operationError on exception`() =
        runTest {
            coEvery { gitSyncRepo.testConnection() } throws IllegalStateException("network down")
            val viewModel = createViewModel()

            viewModel.testGitConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                SettingsViewModel.ConnectionTestState.Error("network down"),
                viewModel.connectionTestState.value,
            )
            assertEquals("network down", viewModel.operationError.value)
        }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            appConfigRepository = appConfigRepository,
            shareServiceManager = shareServiceManager,
            gitSyncRepo = gitSyncRepo,
            syncSchedulerRepository = syncSchedulerRepository,
            syncAndRebuildUseCase = syncAndRebuildUseCase,
        )
}
