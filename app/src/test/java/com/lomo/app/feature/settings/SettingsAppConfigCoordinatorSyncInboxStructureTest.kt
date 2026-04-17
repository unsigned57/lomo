package com.lomo.app.feature.settings

import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.SyncInboxRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsAppConfigCoordinator
 * - Behavior focus: creating the required sync-inbox subdirectories immediately after the user selects a new sync-inbox root.
 * - Observable outcomes: storage-area update forwarding and ensureDirectoryStructure invocation for both path and SAF uri updates.
 * - Red phase: Fails before the fix because choosing a sync-inbox folder only persists the location and never triggers directory creation.
 * - Excludes: repository persistence internals, directory-display loading state, and other settings toggles.
 */
class SettingsAppConfigCoordinatorSyncInboxStructureTest {
    @Test
    fun `updating sync inbox location ensures the required directory structure`() =
        runTest {
            val appConfigRepository: AppConfigRepository = mockk(relaxed = true)
            val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk(relaxed = true)
            val syncInboxRepository: SyncInboxRepository = mockk(relaxed = true)
            every { appConfigRepository.observeRootDisplayName() } returns flowOf(null)
            every { appConfigRepository.observeImageDisplayName() } returns flowOf(null)
            every { appConfigRepository.observeVoiceDisplayName() } returns flowOf(null)
            every { appConfigRepository.observeSyncInboxDisplayName() } returns flowOf(null)
            every { appConfigRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
            every { appConfigRepository.getTimeFormat() } returns flowOf("HH:mm")
            every { appConfigRepository.getThemeMode() } returns flowOf(com.lomo.domain.model.ThemeMode.SYSTEM)
            every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(true)
            every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(true)
            every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(true)
            every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(false)
            every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
            every { appConfigRepository.isScrollbarEnabled() } returns flowOf(true)
            every { appConfigRepository.isAppLockEnabled() } returns flowOf(false)
            every { appConfigRepository.getStorageFilenameFormat() } returns flowOf("default")
            every { appConfigRepository.getStorageTimestampFormat() } returns flowOf("HH:mm")
            every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)
            every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
            every { appConfigRepository.getShareCardSignatureText() } returns flowOf("")
            every { appConfigRepository.isSyncInboxEnabled() } returns flowOf(false)

            val coordinator =
                SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    syncInboxRepository = syncInboxRepository,
                )

            coordinator.updateSyncInboxDirectory("/sync-inbox")
            coordinator.updateSyncInboxUri("content://tree/sync-inbox")

            coVerify(exactly = 1) {
                appConfigRepository.applyLocation(
                    StorageAreaUpdate(StorageArea.SYNC_INBOX, StorageLocation("/sync-inbox")),
                )
            }
            coVerify(exactly = 1) {
                appConfigRepository.applyLocation(
                    StorageAreaUpdate(StorageArea.SYNC_INBOX, StorageLocation("content://tree/sync-inbox")),
                )
            }
            coVerify(exactly = 2) { syncInboxRepository.ensureDirectoryStructure() }
        }
}
