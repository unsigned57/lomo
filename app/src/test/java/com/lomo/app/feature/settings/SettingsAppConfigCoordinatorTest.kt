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
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.WorkspaceStateResolver
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Capability: Settings app config coordination and preference state updates.
 * - Scenarios:
 *   - Given initial state, directory display states remain Loading until values are emitted.
 *   - Given emitted location display names, directory display states are resolved.
 *   - Given root directory updates, switch-root use case is delegated to and updates repository/state.
 *   - Given image/voice/sync inbox directory updates, correct repository storage locations are applied.
 *   - Given preference toggle updates (double-tap, free-text copy), each updates its own repository state and never touches the other.
 *   - Given date/time/theme/haptic/etc. preference updates, correct repository settings are applied.
 * - Observable outcomes:
 *   - Directory display state Flow emissions.
 *   - Backing repository preference states and storage locations.
 *   - Rebuild call count from WorkspaceStateResolver.
 * - TDD proof: Ensures all coordinate actions map directly to repository states without side effects.
 * - Excludes: Datastore file serialization, Android system settings integration.
 */
class SettingsAppConfigCoordinatorTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()
    private val workspaceStateResolver = FakeWorkspaceStateResolver()
    private val switchRootStorageUseCase = SwitchRootStorageUseCase(appConfigRepository, workspaceStateResolver)

    private class FakeWorkspaceStateResolver : WorkspaceStateResolver {
        var rebuildCallCount = 0
        override suspend fun rebuildFromCurrentWorkspace() {
            rebuildCallCount++
        }
    }

    init {
        test("directory display states stay loading until repository emits a value") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.rootDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.imageDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.voiceDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.syncInboxDirectory.value shouldBe DirectoryDisplayState.Loading
                coordinator.dateFormat.value shouldBe PreferenceDefaults.DATE_FORMAT
                coordinator.timeFormat.value shouldBe PreferenceDefaults.TIME_FORMAT
                coordinator.themeMode.value shouldBe ThemeMode.SYSTEM
            }
        }

        test("directory display states resolve emitted values including unset") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )
                backgroundScope.launch { coordinator.rootDirectory.collect {} }
                backgroundScope.launch { coordinator.imageDirectory.collect {} }
                backgroundScope.launch { coordinator.voiceDirectory.collect {} }
                backgroundScope.launch { coordinator.syncInboxDirectory.collect {} }

                appConfigRepository.setDisplayName(StorageArea.ROOT, "/workspace/root")
                appConfigRepository.setDisplayName(StorageArea.IMAGE, null)
                appConfigRepository.setDisplayName(StorageArea.VOICE, "/workspace/voice")
                appConfigRepository.setDisplayName(StorageArea.SYNC_INBOX, "/workspace/inbox")

                coordinator.rootDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved("/workspace/root")
                coordinator.imageDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved(null)
                coordinator.voiceDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved("/workspace/voice")
                coordinator.syncInboxDirectory.first { it !is DirectoryDisplayState.Loading } shouldBe
                    DirectoryDisplayState.Resolved("/workspace/inbox")
            }
        }

        test("updateRootDirectory and updateRootUri delegate to switch-root use case") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.updateRootDirectory("/root/path")
                appConfigRepository.currentRootLocation() shouldBe StorageLocation("/root/path")
                workspaceStateResolver.rebuildCallCount shouldBe 1

                coordinator.updateRootUri("content://tree/root")
                appConfigRepository.currentRootLocation() shouldBe StorageLocation("content://tree/root")
                workspaceStateResolver.rebuildCallCount shouldBe 2
            }
        }

        test("updateImage and updateVoice locations build expected storage-area updates") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.updateImageDirectory("/images")
                appConfigRepository.currentLocation(StorageArea.IMAGE) shouldBe StorageLocation("/images")

                coordinator.updateImageUri("content://tree/images")
                appConfigRepository.currentLocation(StorageArea.IMAGE) shouldBe StorageLocation("content://tree/images")

                coordinator.updateVoiceDirectory("/voice")
                appConfigRepository.currentLocation(StorageArea.VOICE) shouldBe StorageLocation("/voice")

                coordinator.updateVoiceUri("content://tree/voice")
                appConfigRepository.currentLocation(StorageArea.VOICE) shouldBe StorageLocation("content://tree/voice")
            }
        }

        test("updateSyncInbox locations build expected storage-area updates") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.updateSyncInboxDirectory("/sync-inbox")
                appConfigRepository.currentLocation(StorageArea.SYNC_INBOX) shouldBe StorageLocation("/sync-inbox")

                coordinator.updateSyncInboxUri("content://tree/sync-inbox")
                appConfigRepository.currentLocation(StorageArea.SYNC_INBOX) shouldBe StorageLocation("content://tree/sync-inbox")
            }
        }

        test("updateDoubleTapEditEnabled writes only the double-tap preference and never touches free-text-copy") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.updateDoubleTapEditEnabled(true)
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe true
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe PreferenceDefaults.FREE_TEXT_COPY_ENABLED

                coordinator.updateDoubleTapEditEnabled(false)
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe false
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe PreferenceDefaults.FREE_TEXT_COPY_ENABLED
            }
        }

        test("updateFreeTextCopyEnabled writes only the free-text-copy preference and never touches double-tap") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.updateFreeTextCopyEnabled(true)
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe true
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED

                coordinator.updateFreeTextCopyEnabled(false)
                appConfigRepository.isFreeTextCopyEnabled().first() shouldBe false
                appConfigRepository.isDoubleTapEditEnabled().first() shouldBe PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED
            }
        }

        test("preference setters forward values to repository") {
            runTest {
                val coordinator = SettingsAppConfigCoordinator(
                    appConfigRepository,
                    switchRootStorageUseCase,
                    backgroundScope,
                )

                coordinator.updateDateFormat("MM/dd/yyyy")
                appConfigRepository.getDateFormat().first() shouldBe "MM/dd/yyyy"

                coordinator.updateTimeFormat("HH:mm")
                appConfigRepository.getTimeFormat().first() shouldBe "HH:mm"

                coordinator.updateThemeMode(ThemeMode.DARK)
                appConfigRepository.getThemeMode().first() shouldBe ThemeMode.DARK

                coordinator.updateStorageFilenameFormat("yyyyMMdd")
                appConfigRepository.getStorageFilenameFormat().first() shouldBe "yyyyMMdd"

                coordinator.updateStorageTimestampFormat("HH:mm")
                appConfigRepository.getStorageTimestampFormat().first() shouldBe "HH:mm"

                coordinator.updateHapticFeedback(false)
                appConfigRepository.isHapticFeedbackEnabled().first() shouldBe false

                coordinator.updateShowInputHints(false)
                appConfigRepository.isShowInputHintsEnabled().first() shouldBe false

                coordinator.updateQuickSaveOnBackEnabled(true)
                appConfigRepository.isQuickSaveOnBackEnabled().first() shouldBe true

                coordinator.updateAppLockEnabled(true)
                appConfigRepository.isAppLockEnabled().first() shouldBe true

                coordinator.updateCheckUpdatesOnStartup(false)
                appConfigRepository.isCheckUpdatesOnStartupEnabled().first() shouldBe false

                coordinator.updateShareCardShowTime(false)
                appConfigRepository.isShareCardShowTimeEnabled().first() shouldBe false

                coordinator.updateShareCardShowBrand(false)
                appConfigRepository.isShareCardShowBrandEnabled().first() shouldBe false

                coordinator.updateSyncInboxEnabled(true)
                appConfigRepository.isSyncInboxEnabled().first() shouldBe true
            }
        }
    }
}
