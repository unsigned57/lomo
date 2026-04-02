package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsAppConfigCoordinator
 * - Behavior focus: memo snapshot settings flows, preference updates, and destructive cleanup when memo rollback capture is turned off.
 * - Observable outcomes: exposed StateFlow values and ordered repository calls for disabling memo snapshots.
 * - Red phase: Fails if Settings stops exposing memo snapshot preferences or stops clearing memo rollback history when snapshot capture is disabled.
 * - Excludes: Compose rendering, DataStore serialization internals, and removed day-file snapshot UI.
 */
class SettingsAppConfigCoordinatorSnapshotTest {
    private val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk(relaxed = true)
    private val memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository = mockk(relaxed = true)
    private val memoVersionRepository: MemoVersionRepository = mockk(relaxed = true)
    private val appConfigRepository: AppConfigRepository = mockk(relaxed = true)

    @Test
    fun `memo snapshot flows expose repository values`() =
        runTest {
            val memoCount = 50
            val memoDays = 90
            every { memoSnapshotPreferencesRepository.isMemoSnapshotsEnabled() } returns flowOf(false)
            every { memoSnapshotPreferencesRepository.getMemoSnapshotMaxCount() } returns flowOf(memoCount)
            every { memoSnapshotPreferencesRepository.getMemoSnapshotMaxAgeDays() } returns flowOf(memoDays)
            stubAppConfigRepository()

            val coordinator =
                SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    memoVersionRepository = memoVersionRepository,
                )

            assertEquals(false, coordinator.memoSnapshotsEnabled.first { it == false })
            assertEquals(memoCount, coordinator.memoSnapshotMaxCount.first { it == memoCount })
            assertEquals(memoDays, coordinator.memoSnapshotMaxAgeDays.first { it == memoDays })
        }

    @Test
    fun `disabling memo snapshots turns off recording and clears rollback history`() =
        runTest {
            stubAppConfigRepository()
            val coordinator =
                SettingsAppConfigCoordinator(
                    appConfigRepository = appConfigRepository,
                    switchRootStorageUseCase = switchRootStorageUseCase,
                    scope = backgroundScope,
                    memoSnapshotPreferencesRepository = memoSnapshotPreferencesRepository,
                    memoVersionRepository = memoVersionRepository,
                )

            coordinator.updateMemoSnapshotsEnabled(false)

            coVerifyOrder {
                memoSnapshotPreferencesRepository.setMemoSnapshotsEnabled(false)
                memoVersionRepository.clearAllMemoSnapshots()
            }
        }

    private fun stubAppConfigRepository() {
        every { appConfigRepository.observeRootDisplayName() } returns flowOf("")
        every { appConfigRepository.observeImageDisplayName() } returns flowOf("")
        every { appConfigRepository.observeVoiceDisplayName() } returns flowOf("")
        every { appConfigRepository.getDateFormat() } returns flowOf(PreferenceDefaults.DATE_FORMAT)
        every { appConfigRepository.getTimeFormat() } returns flowOf(PreferenceDefaults.TIME_FORMAT)
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(PreferenceDefaults.SHOW_INPUT_HINTS)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)
        every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(PreferenceDefaults.FREE_TEXT_COPY_ENABLED)
        every { appConfigRepository.isMemoActionAutoReorderEnabled() } returns
            flowOf(PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED)
        every { appConfigRepository.isQuickSaveOnBackEnabled() } returns
            flowOf(PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)
        every { appConfigRepository.isAppLockEnabled() } returns flowOf(PreferenceDefaults.APP_LOCK_ENABLED)
        every { appConfigRepository.getStorageFilenameFormat() } returns
            flowOf(PreferenceDefaults.STORAGE_FILENAME_FORMAT)
        every { appConfigRepository.getStorageTimestampFormat() } returns
            flowOf(PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)
        every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns
            flowOf(PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns
            flowOf(PreferenceDefaults.SHARE_CARD_SHOW_TIME)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns
            flowOf(PreferenceDefaults.SHARE_CARD_SHOW_BRAND)
    }
}
