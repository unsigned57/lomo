package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.mockk.coVerify
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
 * - Behavior focus: memo action auto-reorder state exposure and repository write-through for the interaction settings surface.
 * - Observable outcomes: coordinator StateFlow default/current values and forwarded repository setter calls.
 * - Red phase: Fails before the fix because the coordinator does not yet surface the memo action auto-reorder preference or expose an updater for settings UI events.
 * - Excludes: DataStore persistence internals, Compose settings rendering, and unrelated interaction toggles already covered elsewhere.
 */
class SettingsAppConfigCoordinatorMemoActionOrderingTest {
    private val appConfigRepository: AppConfigRepository = mockk(relaxed = true)
    private val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk(relaxed = true)

    @Test
    fun `memo action auto reorder state flow follows repository value`() =
        runTest {
            every { appConfigRepository.observeRootDisplayName() } returns flowOf("")
            every { appConfigRepository.observeImageDisplayName() } returns flowOf("")
            every { appConfigRepository.observeVoiceDisplayName() } returns flowOf("")
            every { appConfigRepository.getDateFormat() } returns flowOf(PreferenceDefaults.DATE_FORMAT)
            every { appConfigRepository.getTimeFormat() } returns flowOf(PreferenceDefaults.TIME_FORMAT)
            every { appConfigRepository.getThemeMode() } returns flowOf(com.lomo.domain.model.ThemeMode.SYSTEM)
            every { appConfigRepository.getStorageFilenameFormat() } returns flowOf(PreferenceDefaults.STORAGE_FILENAME_FORMAT)
            every { appConfigRepository.getStorageTimestampFormat() } returns flowOf(PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)
            every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
            every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(PreferenceDefaults.SHOW_INPUT_HINTS)
            every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)
            every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(PreferenceDefaults.FREE_TEXT_COPY_ENABLED)
            every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)
            every { appConfigRepository.isAppLockEnabled() } returns flowOf(PreferenceDefaults.APP_LOCK_ENABLED)
            every { appConfigRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)
            every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_TIME)
            every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_BRAND)
            every {
                appConfigRepository.isMemoActionAutoReorderEnabled()
            } returns flowOf(false)

            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            assertEquals(false, coordinator.memoActionAutoReorderEnabled.first { value -> !value })
        }

    @Test
    fun `updateMemoActionAutoReorderEnabled forwards value to repository`() =
        runTest {
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            coordinator.updateMemoActionAutoReorderEnabled(false)

            coVerify(exactly = 1) { appConfigRepository.setMemoActionAutoReorderEnabled(false) }
        }
}
