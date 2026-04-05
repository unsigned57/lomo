package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncEngineState
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.LanShareService
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * - Unit under test: SettingsViewModel
 * - Behavior focus: settings operation error mapping, memo-only snapshot state exposure, and delegated settings mutations after quick-send removal.
 * - Observable outcomes: operationError, connectionTestState, uiState.snapshot and uiState.interaction shapes, and collaborator invocations.
 * - Red phase: Fails before the fix because SettingsViewModel still depends on the removed quick-send preference contract.
 * - Excludes: Compose rendering, transport implementation details, and repository internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appConfigRepository: AppConfigRepository
    private lateinit var shareServiceManager: LanShareService
    private lateinit var gitSyncSettingsUseCase: GitSyncSettingsUseCase
    private lateinit var webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase
    private lateinit var s3SyncSettingsUseCase: S3SyncSettingsUseCase
    private lateinit var switchRootStorageUseCase: SwitchRootStorageUseCase
    private lateinit var memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository
    private lateinit var memoVersionRepository: MemoVersionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        appConfigRepository = mockk(relaxed = true)
        shareServiceManager = mockk(relaxed = true)
        gitSyncSettingsUseCase = mockk(relaxed = true)
        webDavSyncSettingsUseCase = mockk(relaxed = true)
        s3SyncSettingsUseCase = mockk(relaxed = true)
        switchRootStorageUseCase = mockk(relaxed = true)
        memoSnapshotPreferencesRepository = mockk(relaxed = true)
        memoVersionRepository = mockk(relaxed = true)

        every { appConfigRepository.observeRootDisplayName() } returns flowOf("")
        every { appConfigRepository.observeImageDisplayName() } returns flowOf("")
        every { appConfigRepository.observeVoiceDisplayName() } returns flowOf("")
        every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
        every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
        every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(false)
        every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
        every { appConfigRepository.getMemoActionOrder() } returns flowOf(emptyList())
        every { appConfigRepository.isAppLockEnabled() } returns flowOf(false)
        every { appConfigRepository.getStorageFilenameFormat() } returns flowOf("default")
        every { appConfigRepository.getStorageTimestampFormat() } returns flowOf("HHmm")
        every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
        every { memoSnapshotPreferencesRepository.isMemoSnapshotsEnabled() } returns
            flowOf(PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED)
        every { memoSnapshotPreferencesRepository.getMemoSnapshotMaxCount() } returns
            flowOf(PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT)
        every { memoSnapshotPreferencesRepository.getMemoSnapshotMaxAgeDays() } returns
            flowOf(PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS)

        every { shareServiceManager.lanShareE2eEnabled } returns flowOf(true)
        every { shareServiceManager.lanSharePairingConfigured } returns flowOf(false)
        every { shareServiceManager.lanShareDeviceName } returns flowOf("Local")

        every { gitSyncSettingsUseCase.observeGitSyncEnabled() } returns flowOf(false)
        every { gitSyncSettingsUseCase.observeRemoteUrl() } returns flowOf("")
        every { gitSyncSettingsUseCase.observeAuthorName() } returns flowOf("Lomo")
        every { gitSyncSettingsUseCase.observeAuthorEmail() } returns flowOf("lomo@example.com")
        every { gitSyncSettingsUseCase.observeAutoSyncEnabled() } returns flowOf(false)
        every { gitSyncSettingsUseCase.observeAutoSyncInterval() } returns flowOf("15m")
        every { gitSyncSettingsUseCase.observeSyncOnRefreshEnabled() } returns flowOf(false)
        every { gitSyncSettingsUseCase.observeLastSyncTimeMillis() } returns flowOf(null)
        every { gitSyncSettingsUseCase.observeSyncState() } returns flowOf(SyncEngineState.Idle)
        every { gitSyncSettingsUseCase.isValidRemoteUrl(any()) } returns true

        every { webDavSyncSettingsUseCase.observeWebDavSyncEnabled() } returns flowOf(false)
        every { webDavSyncSettingsUseCase.observeProvider() } returns flowOf(WebDavProvider.NUTSTORE)
        every { webDavSyncSettingsUseCase.observeBaseUrl() } returns flowOf("")
        every { webDavSyncSettingsUseCase.observeEndpointUrl() } returns flowOf("")
        every { webDavSyncSettingsUseCase.observeUsername() } returns flowOf("")
        every { webDavSyncSettingsUseCase.observeAutoSyncEnabled() } returns flowOf(false)
        every { webDavSyncSettingsUseCase.observeAutoSyncInterval() } returns flowOf("1h")
        every { webDavSyncSettingsUseCase.observeSyncOnRefreshEnabled() } returns flowOf(false)
        every { webDavSyncSettingsUseCase.observeLastSyncTimeMillis() } returns flowOf(null)
        every { webDavSyncSettingsUseCase.observeSyncState() } returns flowOf(WebDavSyncState.Idle)

        every { s3SyncSettingsUseCase.observeS3SyncEnabled() } returns flowOf(false)
        every { s3SyncSettingsUseCase.observeEndpointUrl() } returns flowOf("")
        every { s3SyncSettingsUseCase.observeRegion() } returns flowOf("")
        every { s3SyncSettingsUseCase.observeBucket() } returns flowOf("")
        every { s3SyncSettingsUseCase.observePrefix() } returns flowOf("")
        every { s3SyncSettingsUseCase.observeLocalSyncDirectory() } returns flowOf("")
        every { s3SyncSettingsUseCase.observePathStyle() } returns flowOf(S3PathStyle.AUTO)
        every { s3SyncSettingsUseCase.observeEncryptionMode() } returns flowOf(S3EncryptionMode.NONE)
        every { s3SyncSettingsUseCase.observeRcloneFilenameEncryption() } returns
            flowOf(S3RcloneFilenameEncryption.STANDARD)
        every { s3SyncSettingsUseCase.observeRcloneFilenameEncoding() } returns
            flowOf(S3RcloneFilenameEncoding.BASE64)
        every { s3SyncSettingsUseCase.observeRcloneDirectoryNameEncryption() } returns
            flowOf(PreferenceDefaults.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION)
        every { s3SyncSettingsUseCase.observeRcloneDataEncryptionEnabled() } returns
            flowOf(PreferenceDefaults.S3_RCLONE_DATA_ENCRYPTION_ENABLED)
        every { s3SyncSettingsUseCase.observeRcloneEncryptedSuffix() } returns
            flowOf(PreferenceDefaults.S3_RCLONE_ENCRYPTED_SUFFIX)
        every { s3SyncSettingsUseCase.observeAutoSyncEnabled() } returns flowOf(false)
        every { s3SyncSettingsUseCase.observeAutoSyncInterval() } returns flowOf("1h")
        every { s3SyncSettingsUseCase.observeSyncOnRefreshEnabled() } returns flowOf(false)
        every { s3SyncSettingsUseCase.observeLastSyncTimeMillis() } returns flowOf(null)
        every { s3SyncSettingsUseCase.observeSyncState() } returns flowOf(S3SyncState.Idle)
        coEvery { s3SyncSettingsUseCase.isAccessKeyConfigured() } returns false
        coEvery { s3SyncSettingsUseCase.isSecretAccessKeyConfigured() } returns false
        coEvery { s3SyncSettingsUseCase.isSessionTokenConfigured() } returns false
        coEvery { s3SyncSettingsUseCase.isEncryptionPasswordConfigured() } returns false
        coEvery { s3SyncSettingsUseCase.isEncryptionPassword2Configured() } returns false

        coEvery { webDavSyncSettingsUseCase.isPasswordConfigured() } returns false
        coEvery { webDavSyncSettingsUseCase.testConnection() } returns WebDavSyncResult.Success("ok")

        coEvery { gitSyncSettingsUseCase.isTokenConfigured() } returns false
        coEvery { gitSyncSettingsUseCase.testConnection() } returns GitSyncResult.Success("ok")
        coEvery { gitSyncSettingsUseCase.resetRepository() } returns GitSyncResult.Success("ok")
        coEvery { gitSyncSettingsUseCase.triggerSyncNow() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `triggerGitSyncNow exposes operationError on failure`() =
        runTest {
            coEvery { gitSyncSettingsUseCase.triggerSyncNow() } throws IllegalStateException("sync failed")
            val viewModel = createViewModel()

            viewModel.gitFeature.triggerGitSyncNow()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                SettingsOperationError.Message("Failed to run Git sync: sync failed"),
                viewModel.operationError.value,
            )
        }

    @Test
    fun `testGitConnection exposes structured error state on exception`() =
        runTest {
            coEvery { gitSyncSettingsUseCase.testConnection() } throws IllegalStateException("network down")
            val viewModel = createViewModel()

            viewModel.gitFeature.testGitConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                SettingsGitConnectionTestState.Error(
                    code = GitSyncErrorCode.UNKNOWN,
                    detail = "Failed to test Git connection: network down",
                ),
                viewModel.connectionTestState.value,
            )
            assertNull(viewModel.operationError.value)
        }

    @Test
    fun `testGitConnection keeps structured error code and detail for rendering`() =
        runTest {
            coEvery { gitSyncSettingsUseCase.testConnection() } returns
                GitSyncResult.Error("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall.execute")
            val viewModel = createViewModel()

            viewModel.gitFeature.testGitConnection()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                SettingsGitConnectionTestState.Error(
                    code = GitSyncErrorCode.UNKNOWN,
                    detail = "java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall.execute",
                ),
                viewModel.connectionTestState.value,
            )
        }

    @Test
    fun `updateLanSharePairingCode surfaces validation errors`() =
        runTest {
            coEvery { shareServiceManager.setLanSharePairingCode(any()) } throws IllegalArgumentException("invalid code")
            val viewModel = createViewModel()

            viewModel.lanShareFeature.updateLanSharePairingCode("bad")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE, viewModel.pairingCodeError.value)
        }

    @Test
    fun `updateLanSharePairingCode keeps pairingCodeError clear on cancellation`() =
        runTest {
            coEvery { shareServiceManager.setLanSharePairingCode(any()) } throws CancellationException("cancelled")
            val viewModel = createViewModel()

            viewModel.lanShareFeature.updateLanSharePairingCode("123456")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(viewModel.pairingCodeError.value)
        }

    @Test
    fun `git conflict dialog classification uses structured error code`() =
        runTest {
            val viewModel = createViewModel()

            assertEquals(true, viewModel.gitFeature.shouldShowGitConflictDialog(GitSyncErrorCode.CONFLICT))
            assertEquals(false, viewModel.gitFeature.shouldShowGitConflictDialog(GitSyncErrorCode.UNKNOWN))
        }

    @Test
    fun `snapshot section exposes memo-only controls`() =
        runTest {
            val viewModel = createViewModel()

            assertEquals(
                SnapshotSectionState(
                    memoSnapshotsEnabled = PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED,
                    memoSnapshotMaxCount = PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT,
                    memoSnapshotMaxAgeDays = PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS,
                ),
                viewModel.uiState.value.snapshot,
            )
        }

    @Test
    fun `updateAppLockEnabled delegates to repository`() =
        runTest {
            coEvery { appConfigRepository.setAppLockEnabled(true) } returns Unit
            val viewModel = createViewModel()

            viewModel.interactionFeature.updateAppLockEnabled(true)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { appConfigRepository.setAppLockEnabled(true) }
        }

    @Test
    fun `ui state exposes s3 defaults from settings use case`() =
        runTest {
            every { s3SyncSettingsUseCase.observeS3SyncEnabled() } returns flowOf(true)
            every { s3SyncSettingsUseCase.observeBucket() } returns flowOf("vault")
            every { s3SyncSettingsUseCase.observeLocalSyncDirectory() } returns
                flowOf("content://tree/primary%3AObsidian")
            every { s3SyncSettingsUseCase.observePathStyle() } returns flowOf(S3PathStyle.PATH_STYLE)
            every { s3SyncSettingsUseCase.observeEncryptionMode() } returns flowOf(S3EncryptionMode.RCLONE_CRYPT)
            every { s3SyncSettingsUseCase.observeRcloneFilenameEncryption() } returns
                flowOf(S3RcloneFilenameEncryption.OFF)
            every { s3SyncSettingsUseCase.observeRcloneFilenameEncoding() } returns
                flowOf(S3RcloneFilenameEncoding.BASE32768)
            every { s3SyncSettingsUseCase.observeRcloneDirectoryNameEncryption() } returns flowOf(false)
            every { s3SyncSettingsUseCase.observeRcloneDataEncryptionEnabled() } returns flowOf(false)
            every { s3SyncSettingsUseCase.observeRcloneEncryptedSuffix() } returns flowOf("none")
            coEvery { s3SyncSettingsUseCase.isEncryptionPassword2Configured() } returns true

            val viewModel = createViewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.s3.enabled)
            assertEquals("vault", viewModel.uiState.value.s3.bucket)
            assertEquals("content://tree/primary%3AObsidian", viewModel.uiState.value.s3.localSyncDirectory)
            assertEquals(S3PathStyle.PATH_STYLE, viewModel.uiState.value.s3.pathStyle)
            assertEquals(S3EncryptionMode.RCLONE_CRYPT, viewModel.uiState.value.s3.encryptionMode)
            assertEquals(S3RcloneFilenameEncryption.OFF, viewModel.uiState.value.s3.rcloneFilenameEncryption)
            assertEquals(S3RcloneFilenameEncoding.BASE32768, viewModel.uiState.value.s3.rcloneFilenameEncoding)
            assertEquals(false, viewModel.uiState.value.s3.rcloneDirectoryNameEncryption)
            assertEquals(false, viewModel.uiState.value.s3.rcloneDataEncryptionEnabled)
            assertEquals("none", viewModel.uiState.value.s3.rcloneEncryptedSuffix)
            assertEquals(true, viewModel.uiState.value.s3.encryptionPassword2Configured)
        }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            coordinatorFactory =
                SettingsCoordinatorFactory(
                    appConfigRepository = appConfigRepository,
                    lanShareService = shareServiceManager,
                    gitSyncSettingsUseCase = gitSyncSettingsUseCase,
                    webDavSyncSettingsUseCase = webDavSyncSettingsUseCase,
                    s3SyncSettingsUseCase = s3SyncSettingsUseCase,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    memoVersionRepository = memoVersionRepository,
                ),
        )
}
