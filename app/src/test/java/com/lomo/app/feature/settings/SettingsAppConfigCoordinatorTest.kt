package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageAreaUpdate
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsAppConfigCoordinator
 * - Behavior focus: directory display loading and resolution state, preference state defaults, root or media location updates, preference setter forwarding, and doubleTap/freeTextCopy mutual exclusion.
 * - Observable outcomes: exposed StateFlow values and repository or use-case calls with expected arguments.
 * - Red phase: Fails before the fix because directory display state is flattened to an empty string, so the settings screen cannot distinguish first-frame loading from an actual unset directory.
 * - Excludes: DataStore persistence internals, Compose rendering, and unrelated settings coordinators.
 */
class SettingsAppConfigCoordinatorTest {
    private val appConfigRepository: AppConfigRepository = mockk(relaxed = true)
    private val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk(relaxed = true)

    private val rootDisplayFlow = MutableSharedFlow<String?>(replay = 1)
    private val imageDisplayFlow = MutableSharedFlow<String?>(replay = 1)
    private val voiceDisplayFlow = MutableSharedFlow<String?>(replay = 1)

    @Before
    fun setUp() {
        every { appConfigRepository.observeRootDisplayName() } returns rootDisplayFlow
        every { appConfigRepository.observeImageDisplayName() } returns imageDisplayFlow
        every { appConfigRepository.observeVoiceDisplayName() } returns voiceDisplayFlow

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
    }

    @Test
    fun `directory display states stay loading until repository emits a value`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            assertEquals(DirectoryDisplayState.Loading, coordinator.rootDirectory.value)
            assertEquals(DirectoryDisplayState.Loading, coordinator.imageDirectory.value)
            assertEquals(DirectoryDisplayState.Loading, coordinator.voiceDirectory.value)
            assertEquals(PreferenceDefaults.DATE_FORMAT, coordinator.dateFormat.value)
            assertEquals(PreferenceDefaults.TIME_FORMAT, coordinator.timeFormat.value)
            assertEquals(ThemeMode.SYSTEM, coordinator.themeMode.value)
        }

    @Test
    fun `directory display states resolve emitted values including unset`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)
            backgroundScope.launch { coordinator.rootDirectory.collect {} }
            backgroundScope.launch { coordinator.imageDirectory.collect {} }
            backgroundScope.launch { coordinator.voiceDirectory.collect {} }

            rootDisplayFlow.emit("/workspace/root")
            imageDisplayFlow.emit(null)
            voiceDisplayFlow.emit("/workspace/voice")

            assertEquals(
                DirectoryDisplayState.Resolved("/workspace/root"),
                coordinator.rootDirectory.first { it !is DirectoryDisplayState.Loading },
            )
            assertEquals(
                DirectoryDisplayState.Resolved(null),
                coordinator.imageDirectory.first { it !is DirectoryDisplayState.Loading },
            )
            assertEquals(
                DirectoryDisplayState.Resolved("/workspace/voice"),
                coordinator.voiceDirectory.first { it !is DirectoryDisplayState.Loading },
            )
        }

    @Test
    fun `updateRootDirectory and updateRootUri delegate to switch-root use case`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            coordinator.updateRootDirectory("/root/path")
            coordinator.updateRootUri("content://tree/root")

            coVerify(exactly = 1) {
                switchRootStorageUseCase.updateRootLocation(StorageLocation("/root/path"))
            }
            coVerify(exactly = 1) {
                switchRootStorageUseCase.updateRootLocation(StorageLocation("content://tree/root"))
            }
        }

    @Test
    fun `updateImage and updateVoice locations build expected storage-area updates`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

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

    @Test
    fun `updateDoubleTapEditEnabled enforces free-text-copy off only when enabling`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            coordinator.updateDoubleTapEditEnabled(true)
            coordinator.updateDoubleTapEditEnabled(false)

            coVerify(exactly = 1) { appConfigRepository.setDoubleTapEditEnabled(true) }
            coVerify(exactly = 1) { appConfigRepository.setDoubleTapEditEnabled(false) }
            coVerify(exactly = 1) { appConfigRepository.setFreeTextCopyEnabled(false) }
        }

    @Test
    fun `updateFreeTextCopyEnabled enforces double-tap off only when enabling`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            coordinator.updateFreeTextCopyEnabled(true)
            coordinator.updateFreeTextCopyEnabled(false)

            coVerify(exactly = 1) { appConfigRepository.setFreeTextCopyEnabled(true) }
            coVerify(exactly = 1) { appConfigRepository.setFreeTextCopyEnabled(false) }
            coVerify(exactly = 1) { appConfigRepository.setDoubleTapEditEnabled(false) }
        }

    @Test
    fun `preference setters forward values to repository`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

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
        }
}
