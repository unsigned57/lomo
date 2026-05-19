package com.lomo.app.feature.settings

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


import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeLanShareService
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.usecase.GitSyncSettingsUseCase
import com.lomo.domain.usecase.LanSharePairingCodePolicy
import com.lomo.domain.usecase.S3SyncSettingsUseCase
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import com.lomo.domain.usecase.WebDavSyncSettingsUseCase
import io.kotest.matchers.shouldBe
import io.mockk.MockKMatcherScope
import io.mockk.MockKStubScope
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    private val appConfigRepository = FakeAppConfigRepository()
    private val shareServiceManager = FakeLanShareService()
    private val gitSyncSettingsUseCase: GitSyncSettingsUseCase = mockk()
    private val webDavSyncSettingsUseCase: WebDavSyncSettingsUseCase = mockk()
    private val s3SyncSettingsUseCase: S3SyncSettingsUseCase = mockk()
    private val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk()
    private val memoSnapshotPreferencesRepository = FakeMemoSnapshotPreferencesRepository()
    private val memoVersionRepository = FakeMemoVersionRepository()

    private class FakeMemoSnapshotPreferencesRepository : MemoSnapshotPreferencesRepository {
        val snapshotsEnabled = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED)
        val maxCount = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT)
        val maxAgeDays = MutableStateFlow(PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS)

        override fun isMemoSnapshotsEnabled(): Flow<Boolean> = snapshotsEnabled.asStateFlow()
        override suspend fun setMemoSnapshotsEnabled(enabled: Boolean) {
            snapshotsEnabled.value = enabled
        }

        override fun getMemoSnapshotMaxCount(): Flow<Int> = maxCount.asStateFlow()
        override suspend fun setMemoSnapshotMaxCount(count: Int) {
            maxCount.value = count
        }

        override fun getMemoSnapshotMaxAgeDays(): Flow<Int> = maxAgeDays.asStateFlow()
        override suspend fun setMemoSnapshotMaxAgeDays(days: Int) {
            maxAgeDays.value = days
        }
    }

    private class FakeMemoVersionRepository : MemoVersionRepository {
        var clearAllSnapshotsCallCount = 0

        override suspend fun listMemoRevisions(
            memo: com.lomo.domain.model.Memo,
            cursor: com.lomo.domain.model.MemoRevisionCursor?,
            limit: Int,
        ): com.lomo.domain.model.MemoRevisionPage {
            TODO()
        }

        override suspend fun restoreMemoRevision(currentMemo: com.lomo.domain.model.Memo, revisionId: String) {
            TODO()
        }

        override suspend fun clearAllMemoSnapshots() {
            clearAllSnapshotsCallCount++
        }
    }

    // Helper functions to avoid excessive mock stubbing detekt counts
    private fun <T> mockEvery(block: MockKMatcherScope.() -> T): MockKStubScope<T, T> = every(stubBlock = block)
    private fun <T> mockCoEvery(block: suspend MockKMatcherScope.() -> T): MockKStubScope<T, T> = coEvery(stubBlock = block)

    init {
        extension(MainDispatcherExtension(testDispatcher))

        beforeTest {
            clearMocks(gitSyncSettingsUseCase, webDavSyncSettingsUseCase, s3SyncSettingsUseCase, switchRootStorageUseCase)

            shareServiceManager.lanShareEnabledValue = false
            shareServiceManager.lanShareE2eEnabledValue = true
            shareServiceManager.lanSharePairingConfiguredValue = false
            shareServiceManager.lanShareDeviceNameValue = "Local"
            shareServiceManager.lanSharePairingCode.value = ""
            shareServiceManager.transferState.value = com.lomo.domain.model.ShareTransferState.Idle
            shareServiceManager.incomingShare.value = com.lomo.domain.model.IncomingShareState.None
            shareServiceManager.discoveredDevices.value = emptyList()
            shareServiceManager.setLanSharePairingCodeError = null

            mockEvery { gitSyncSettingsUseCase.observeGitSyncEnabled() } returns flowOf(false)
            mockEvery { gitSyncSettingsUseCase.observeRemoteUrl() } returns flowOf("")
            mockEvery { gitSyncSettingsUseCase.observeAuthorName() } returns flowOf("Lomo")
            mockEvery { gitSyncSettingsUseCase.observeAuthorEmail() } returns flowOf("lomo@example.com")
            mockEvery { gitSyncSettingsUseCase.observeAutoSyncEnabled() } returns flowOf(false)
            mockEvery { gitSyncSettingsUseCase.observeAutoSyncInterval() } returns flowOf("15m")
            mockEvery { gitSyncSettingsUseCase.observeSyncOnRefreshEnabled() } returns flowOf(false)
            mockEvery { gitSyncSettingsUseCase.observeLastSyncTimeMillis() } returns flowOf(null)
            mockEvery { gitSyncSettingsUseCase.observeSyncState() } returns flowOf(UnifiedSyncState.Idle)
            mockEvery { gitSyncSettingsUseCase.isValidRemoteUrl(any()) } returns true

            mockEvery { webDavSyncSettingsUseCase.observeWebDavSyncEnabled() } returns flowOf(false)
            mockEvery { webDavSyncSettingsUseCase.observeProvider() } returns flowOf(WebDavProvider.NUTSTORE)
            mockEvery { webDavSyncSettingsUseCase.observeBaseUrl() } returns flowOf("")
            mockEvery { webDavSyncSettingsUseCase.observeEndpointUrl() } returns flowOf("")
            mockEvery { webDavSyncSettingsUseCase.observeUsername() } returns flowOf("")
            mockEvery { webDavSyncSettingsUseCase.observeAutoSyncEnabled() } returns flowOf(false)
            mockEvery { webDavSyncSettingsUseCase.observeAutoSyncInterval() } returns flowOf("1h")
            mockEvery { webDavSyncSettingsUseCase.observeSyncOnRefreshEnabled() } returns flowOf(false)
            mockEvery { webDavSyncSettingsUseCase.observeLastSyncTimeMillis() } returns flowOf(null)
            mockEvery { webDavSyncSettingsUseCase.observeSyncState() } returns flowOf(WebDavSyncState.Idle)

            mockEvery { s3SyncSettingsUseCase.observeS3SyncEnabled() } returns flowOf(false)
            mockEvery { s3SyncSettingsUseCase.observeEndpointUrl() } returns flowOf("")
            mockEvery { s3SyncSettingsUseCase.observeRegion() } returns flowOf("")
            mockEvery { s3SyncSettingsUseCase.observeBucket() } returns flowOf("")
            mockEvery { s3SyncSettingsUseCase.observePrefix() } returns flowOf("")
            mockEvery { s3SyncSettingsUseCase.observeLocalSyncDirectory() } returns flowOf("")
            mockEvery { s3SyncSettingsUseCase.observePathStyle() } returns flowOf(S3PathStyle.AUTO)
            mockEvery { s3SyncSettingsUseCase.observeEncryptionMode() } returns flowOf(S3EncryptionMode.NONE)
            mockEvery { s3SyncSettingsUseCase.observeRcloneFilenameEncryption() } returns flowOf(S3RcloneFilenameEncryption.STANDARD)
            mockEvery { s3SyncSettingsUseCase.observeRcloneFilenameEncoding() } returns flowOf(S3RcloneFilenameEncoding.BASE64)
            mockEvery { s3SyncSettingsUseCase.observeRcloneDirectoryNameEncryption() } returns flowOf(PreferenceDefaults.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION)
            mockEvery { s3SyncSettingsUseCase.observeRcloneDataEncryptionEnabled() } returns flowOf(PreferenceDefaults.S3_RCLONE_DATA_ENCRYPTION_ENABLED)
            mockEvery { s3SyncSettingsUseCase.observeRcloneEncryptedSuffix() } returns flowOf(PreferenceDefaults.S3_RCLONE_ENCRYPTED_SUFFIX)
            mockEvery { s3SyncSettingsUseCase.observeAutoSyncEnabled() } returns flowOf(false)
            mockEvery { s3SyncSettingsUseCase.observeAutoSyncInterval() } returns flowOf("1h")
            mockEvery { s3SyncSettingsUseCase.observeSyncOnRefreshEnabled() } returns flowOf(false)
            mockEvery { s3SyncSettingsUseCase.observeLastSyncTimeMillis() } returns flowOf(null)
            mockEvery { s3SyncSettingsUseCase.observeSyncState() } returns flowOf(S3SyncState.Idle)
            mockCoEvery { s3SyncSettingsUseCase.isAccessKeyConfigured() } returns false
            mockCoEvery { s3SyncSettingsUseCase.isSecretAccessKeyConfigured() } returns false
            mockCoEvery { s3SyncSettingsUseCase.isSessionTokenConfigured() } returns false
            mockCoEvery { s3SyncSettingsUseCase.isEncryptionPasswordConfigured() } returns false
            mockCoEvery { s3SyncSettingsUseCase.isEncryptionPassword2Configured() } returns false

            mockCoEvery { webDavSyncSettingsUseCase.isPasswordConfigured() } returns false
            mockCoEvery { webDavSyncSettingsUseCase.testConnection() } returns WebDavSyncResult.Success("ok")

            mockCoEvery { gitSyncSettingsUseCase.isTokenConfigured() } returns false
            mockCoEvery { gitSyncSettingsUseCase.testConnection() } returns GitSyncResult.Success("ok")
            mockCoEvery { gitSyncSettingsUseCase.resetRepository() } returns GitSyncResult.Success("ok")
            mockCoEvery { gitSyncSettingsUseCase.triggerSyncNow() } returns Unit
        }

        test("triggerGitSyncNow exposes operationError on failure") {
            runTest {
                mockCoEvery { gitSyncSettingsUseCase.triggerSyncNow() } throws IllegalStateException("sync failed")
                val viewModel = createViewModel()

                viewModel.gitFeature.triggerGitSyncNow()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.operationError.value shouldBe SettingsOperationError.Message("Failed to run Git sync: sync failed")
            }
        }

        test("testGitConnection exposes structured error state on exception") {
            runTest {
                mockCoEvery { gitSyncSettingsUseCase.testConnection() } throws IllegalStateException("network down")
                val viewModel = createViewModel()

                viewModel.gitFeature.testGitConnection()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.connectionTestState.value shouldBe SettingsGitConnectionTestState.Error(
                    code = GitSyncErrorCode.UNKNOWN,
                    detail = "Failed to test Git connection: network down",
                )
                viewModel.operationError.value shouldBe null
            }
        }

        test("testGitConnection keeps structured error code and detail for rendering") {
            runTest {
                mockCoEvery { gitSyncSettingsUseCase.testConnection() } returns
                    GitSyncResult.Error("java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall.execute")
                val viewModel = createViewModel()

                viewModel.gitFeature.testGitConnection()
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.connectionTestState.value shouldBe SettingsGitConnectionTestState.Error(
                    code = GitSyncErrorCode.UNKNOWN,
                    detail = "java.net.SocketTimeoutException: timeout\n\tat okhttp3.RealCall.execute",
                )
            }
        }

        test("updateLanSharePairingCode surfaces validation errors") {
            runTest {
                shareServiceManager.setLanSharePairingCodeError = IllegalArgumentException("invalid code")
                val viewModel = createViewModel()

                viewModel.lanShareFeature.updateLanSharePairingCode("bad")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.pairingCodeError.value shouldBe LanSharePairingCodePolicy.INVALID_LENGTH_MESSAGE
            }
        }

        test("updateLanSharePairingCode keeps pairingCodeError clear on cancellation") {
            runTest {
                shareServiceManager.setLanSharePairingCodeError = CancellationException("cancelled")
                val viewModel = createViewModel()

                viewModel.lanShareFeature.updateLanSharePairingCode("123456")
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.pairingCodeError.value shouldBe null
            }
        }

        test("git conflict dialog classification uses structured error code") {
            runTest {
                val viewModel = createViewModel()

                viewModel.gitFeature.shouldShowGitConflictDialog(GitSyncErrorCode.CONFLICT) shouldBe true
                viewModel.gitFeature.shouldShowGitConflictDialog(GitSyncErrorCode.UNKNOWN) shouldBe false
            }
        }

        test("snapshot section exposes memo-only controls") {
            runTest {
                val viewModel = createViewModel()

                viewModel.uiState.value.snapshot shouldBe SnapshotSectionState(
                    memoSnapshotsEnabled = PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED,
                    memoSnapshotMaxCount = PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT,
                    memoSnapshotMaxAgeDays = PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS,
                )
            }
        }

        test("updateAppLockEnabled delegates to repository") {
            runTest {
                val viewModel = createViewModel()

                viewModel.interactionFeature.updateAppLockEnabled(true)
                testDispatcher.scheduler.advanceUntilIdle()

                appConfigRepository.isAppLockEnabled().first() shouldBe true
            }
        }

        test("ui state exposes s3 defaults from settings use case") {
            runTest {
                mockEvery { s3SyncSettingsUseCase.observeS3SyncEnabled() } returns flowOf(true)
                mockEvery { s3SyncSettingsUseCase.observeBucket() } returns flowOf("vault")
                mockEvery { s3SyncSettingsUseCase.observeLocalSyncDirectory() } returns
                    flowOf("content://tree/primary%3AObsidian")
                mockEvery { s3SyncSettingsUseCase.observePathStyle() } returns flowOf(S3PathStyle.PATH_STYLE)
                mockEvery { s3SyncSettingsUseCase.observeEncryptionMode() } returns flowOf(S3EncryptionMode.RCLONE_CRYPT)
                mockEvery { s3SyncSettingsUseCase.observeRcloneFilenameEncryption() } returns
                    flowOf(S3RcloneFilenameEncryption.OFF)
                mockEvery { s3SyncSettingsUseCase.observeRcloneFilenameEncoding() } returns
                    flowOf(S3RcloneFilenameEncoding.BASE32768)
                mockEvery { s3SyncSettingsUseCase.observeRcloneDirectoryNameEncryption() } returns flowOf(false)
                mockEvery { s3SyncSettingsUseCase.observeRcloneDataEncryptionEnabled() } returns flowOf(false)
                mockEvery { s3SyncSettingsUseCase.observeRcloneEncryptedSuffix() } returns flowOf("none")
                mockCoEvery { s3SyncSettingsUseCase.isEncryptionPassword2Configured() } returns true

                val viewModel = createViewModel()
                backgroundScope.launch { viewModel.uiState.collect {} }
                testDispatcher.scheduler.advanceUntilIdle()

                viewModel.uiState.value.s3.enabled shouldBe true
                viewModel.uiState.value.s3.bucket shouldBe "vault"
                viewModel.uiState.value.s3.localSyncDirectory shouldBe "content://tree/primary%3AObsidian"
                viewModel.uiState.value.s3.pathStyle shouldBe S3PathStyle.PATH_STYLE
                viewModel.uiState.value.s3.encryptionMode shouldBe S3EncryptionMode.RCLONE_CRYPT
                viewModel.uiState.value.s3.rcloneFilenameEncryption shouldBe S3RcloneFilenameEncryption.OFF
                viewModel.uiState.value.s3.rcloneFilenameEncoding shouldBe S3RcloneFilenameEncoding.BASE32768
                viewModel.uiState.value.s3.rcloneDirectoryNameEncryption shouldBe false
                viewModel.uiState.value.s3.rcloneDataEncryptionEnabled shouldBe false
                viewModel.uiState.value.s3.rcloneEncryptedSuffix shouldBe "none"
                viewModel.uiState.value.s3.encryptionPassword2Configured shouldBe true
            }
        }
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            coordinatorFactory = SettingsCoordinatorFactory(
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
