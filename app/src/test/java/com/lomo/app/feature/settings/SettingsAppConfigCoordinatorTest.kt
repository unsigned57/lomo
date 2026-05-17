package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: SettingsAppConfigCoordinator.
 * - Behavior focus: directory display loading and resolution state, preference state
 *   defaults, root or media location updates, preference setter forwarding, and — critically
 *   — the double-tap-to-edit and free-text-copy preferences are **independent** toggles:
 *   neither one writes the other behind the user's back. The two gestures coexist at the
 *   composable layer (long-press for selection, double-tap for editor), so the settings UI
 *   no longer needs the historical "two-of-three" mutual exclusion that toggled the
 *   sibling preference off when one was enabled.
 * - Observable outcomes: exposed StateFlow values and repository or use-case calls with
 *   expected arguments, including the *absence* of cross-setter calls for the two
 *   gesture-related preferences.
 * - Red phase: Fails before the fix because updateDoubleTapEditEnabled(true) currently
 *   also writes setFreeTextCopyEnabled(false) (and vice versa), so the "no cross-setter"
 *   coVerify assertions break.
 * - Excludes: DataStore persistence internals, Compose rendering, and unrelated settings
 *   coordinators.
 */
class SettingsAppConfigCoordinatorTest : AppFunSpec() {
    private val appConfigRepository: AppConfigRepository = mockk(relaxed = true)
    private val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk(relaxed = true)

    private val rootDisplayFlow = MutableSharedFlow<String?>(replay = 1)
    private val imageDisplayFlow = MutableSharedFlow<String?>(replay = 1)
    private val voiceDisplayFlow = MutableSharedFlow<String?>(replay = 1)
    private val syncInboxDisplayFlow = MutableSharedFlow<String?>(replay = 1)

    init {
        beforeTest {
            every { appConfigRepository.observeRootDisplayName() } returns rootDisplayFlow
            every { appConfigRepository.observeImageDisplayName() } returns imageDisplayFlow
            every { appConfigRepository.observeVoiceDisplayName() } returns voiceDisplayFlow
            every { appConfigRepository.observeSyncInboxDisplayName() } returns syncInboxDisplayFlow

            every { appConfigRepository.getDateFormat() } returns flowOf(PreferenceDefaults.DATE_FORMAT)
            every { appConfigRepository.getTimeFormat() } returns flowOf(PreferenceDefaults.TIME_FORMAT)
            every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
            every {
                appConfigRepository.getStorageFilenameFormat()
            } returns flowOf(PreferenceDefaults.STORAGE_FILENAME_FORMAT)
            every {
                appConfigRepository.getStorageTimestampFormat()
            } returns flowOf(PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)

            every {
                appConfigRepository.isHapticFeedbackEnabled()
            } returns flowOf(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
            every {
                appConfigRepository.isShowInputHintsEnabled()
            } returns flowOf(PreferenceDefaults.SHOW_INPUT_HINTS)
            every {
                appConfigRepository.isDoubleTapEditEnabled()
            } returns flowOf(PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)
            every {
                appConfigRepository.isFreeTextCopyEnabled()
            } returns flowOf(PreferenceDefaults.FREE_TEXT_COPY_ENABLED)
            every {
                appConfigRepository.isMemoActionAutoReorderEnabled()
            } returns flowOf(PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED)
            every {
                appConfigRepository.isQuickSaveOnBackEnabled()
            } returns flowOf(PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)
            every {
                appConfigRepository.isAppLockEnabled()
            } returns flowOf(PreferenceDefaults.APP_LOCK_ENABLED)
            every {
                appConfigRepository.isCheckUpdatesOnStartupEnabled()
            } returns flowOf(PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)
            every {
                appConfigRepository.isShareCardShowTimeEnabled()
            } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_TIME)
            every {
                appConfigRepository.isShareCardShowBrandEnabled()
            } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_BRAND)
            every { appConfigRepository.isSyncInboxEnabled() } returns flowOf(false)
        }

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

                rootDisplayFlow.emit("/workspace/root")
                imageDisplayFlow.emit(null)
                voiceDisplayFlow.emit("/workspace/voice")
                syncInboxDisplayFlow.emit("/workspace/inbox")

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
                coordinator.updateRootUri("content://tree/root")

                coVerify(exactly = 1) {
                    switchRootStorageUseCase.updateRootLocation(StorageLocation("/root/path"))
                }
                coVerify(exactly = 1) {
                    switchRootStorageUseCase.updateRootLocation(StorageLocation("content://tree/root"))
                }
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
                coordinator.updateImageUri("content://tree/images")
                coordinator.updateVoiceDirectory("/voice")
                coordinator.updateVoiceUri("content://tree/voice")

                coVerify(exactly = 1) {
                    appConfigRepository.applyLocation(
                        StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("/images")),
                    )
                }
                coVerify(exactly = 1) {
                    appConfigRepository.applyLocation(
                        StorageAreaUpdate(StorageArea.IMAGE, StorageLocation("content://tree/images")),
                    )
                }
                coVerify(exactly = 1) {
                    appConfigRepository.applyLocation(
                        StorageAreaUpdate(StorageArea.VOICE, StorageLocation("/voice")),
                    )
                }
                coVerify(exactly = 1) {
                    appConfigRepository.applyLocation(
                        StorageAreaUpdate(StorageArea.VOICE, StorageLocation("content://tree/voice")),
                    )
                }
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
                coordinator.updateDoubleTapEditEnabled(false)

                coVerify(exactly = 1) { appConfigRepository.setDoubleTapEditEnabled(true) }
                coVerify(exactly = 1) { appConfigRepository.setDoubleTapEditEnabled(false) }
                coVerify(exactly = 0) { appConfigRepository.setFreeTextCopyEnabled(any()) }
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
                coordinator.updateFreeTextCopyEnabled(false)

                coVerify(exactly = 1) { appConfigRepository.setFreeTextCopyEnabled(true) }
                coVerify(exactly = 1) { appConfigRepository.setFreeTextCopyEnabled(false) }
                coVerify(exactly = 0) { appConfigRepository.setDoubleTapEditEnabled(any()) }
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
                coordinator.updateTimeFormat("HH:mm")
                coordinator.updateThemeMode(ThemeMode.DARK)
                coordinator.updateStorageFilenameFormat("yyyyMMdd")
                coordinator.updateStorageTimestampFormat("HH:mm")
                coordinator.updateHapticFeedback(false)
                coordinator.updateShowInputHints(false)
                coordinator.updateQuickSaveOnBackEnabled(true)
                coordinator.updateAppLockEnabled(true)
                coordinator.updateCheckUpdatesOnStartup(false)
                coordinator.updateShareCardShowTime(false)
                coordinator.updateShareCardShowBrand(false)
                coordinator.updateSyncInboxEnabled(true)

                coVerify(exactly = 1) { appConfigRepository.setDateFormat("MM/dd/yyyy") }
                coVerify(exactly = 1) { appConfigRepository.setTimeFormat("HH:mm") }
                coVerify(exactly = 1) { appConfigRepository.setThemeMode(ThemeMode.DARK) }
                coVerify(exactly = 1) { appConfigRepository.setStorageFilenameFormat("yyyyMMdd") }
                coVerify(exactly = 1) { appConfigRepository.setStorageTimestampFormat("HH:mm") }
                coVerify(exactly = 1) { appConfigRepository.setHapticFeedbackEnabled(false) }
                coVerify(exactly = 1) { appConfigRepository.setShowInputHintsEnabled(false) }
                coVerify(exactly = 1) { appConfigRepository.setQuickSaveOnBackEnabled(true) }
                coVerify(exactly = 1) { appConfigRepository.setAppLockEnabled(true) }
                coVerify(exactly = 1) { appConfigRepository.setCheckUpdatesOnStartup(false) }
                coVerify(exactly = 1) { appConfigRepository.setShareCardShowTime(false) }
                coVerify(exactly = 1) { appConfigRepository.setShareCardShowBrand(false) }
                coVerify(exactly = 1) { appConfigRepository.setSyncInboxEnabled(true) }
            }
        }
    }
}
