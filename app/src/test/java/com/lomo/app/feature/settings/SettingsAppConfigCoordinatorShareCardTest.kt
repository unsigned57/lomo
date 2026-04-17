package com.lomo.app.feature.settings

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.usecase.SwitchRootStorageUseCase
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsAppConfigCoordinator
 * - Behavior focus: share-card settings expose and update signature text alongside the remaining footer toggles after recorded-days removal.
 * - Observable outcomes: exposed StateFlow values and forwarded repository update calls.
 * - Red phase: Fails before the fix because SettingsAppConfigCoordinator still exposes and forwards the removed recorded-days setting.
 * - Test Change Justification: reason category = product contract changed; the old assertions covered recorded-days exposure and updates, which are no longer valid after the setting was deleted. Coverage is preserved by keeping signature-text exposure and update assertions on the remaining share-card settings path. This is not fitting the test to the implementation because the product requirement explicitly removed recorded-days from settings.
 * - Excludes: Compose rendering, DataStore serialization, and bitmap rendering.
 */
class SettingsAppConfigCoordinatorShareCardTest {
    private val appConfigRepository: AppConfigRepository = mockk(relaxed = true)
    private val switchRootStorageUseCase: SwitchRootStorageUseCase = mockk(relaxed = true)

    @Test
    fun `share card settings expose signature text`() =
        runTest {
            stubRepository(signatureText = "Unsigned57")

            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            assertEquals("Unsigned57", coordinator.shareCardSignatureText.drop(1).first())
        }

    @Test
    fun `share card settings forward signature text updates`() =
        runTest {
            stubRepository(signatureText = "Lomo")
            val coordinator = SettingsAppConfigCoordinator(appConfigRepository, switchRootStorageUseCase, backgroundScope)

            coordinator.updateShareCardSignatureText("Unsigned57")

            coVerify(exactly = 1) { appConfigRepository.setShareCardSignatureText("Unsigned57") }
        }

    private fun stubRepository(
        signatureText: String,
    ) {
        every { appConfigRepository.observeRootDisplayName() } returns flowOf("")
        every { appConfigRepository.observeImageDisplayName() } returns flowOf("")
        every { appConfigRepository.observeVoiceDisplayName() } returns flowOf("")
        every { appConfigRepository.observeSyncInboxDisplayName() } returns flowOf("")
        every { appConfigRepository.getDateFormat() } returns flowOf(PreferenceDefaults.DATE_FORMAT)
        every { appConfigRepository.getTimeFormat() } returns flowOf(PreferenceDefaults.TIME_FORMAT)
        every { appConfigRepository.getThemeMode() } returns flowOf(ThemeMode.SYSTEM)
        every { appConfigRepository.isHapticFeedbackEnabled() } returns flowOf(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
        every { appConfigRepository.isShowInputHintsEnabled() } returns flowOf(PreferenceDefaults.SHOW_INPUT_HINTS)
        every { appConfigRepository.isDoubleTapEditEnabled() } returns flowOf(PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED)
        every { appConfigRepository.isFreeTextCopyEnabled() } returns flowOf(PreferenceDefaults.FREE_TEXT_COPY_ENABLED)
        every {
            appConfigRepository.isMemoActionAutoReorderEnabled()
        } returns flowOf(PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED)
        every { appConfigRepository.isQuickSaveOnBackEnabled() } returns flowOf(PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED)
        every { appConfigRepository.isAppLockEnabled() } returns flowOf(PreferenceDefaults.APP_LOCK_ENABLED)
        every { appConfigRepository.getStorageFilenameFormat() } returns flowOf(PreferenceDefaults.STORAGE_FILENAME_FORMAT)
        every { appConfigRepository.getStorageTimestampFormat() } returns flowOf(PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT)
        every {
            appConfigRepository.isCheckUpdatesOnStartupEnabled()
        } returns flowOf(PreferenceDefaults.CHECK_UPDATES_ON_STARTUP)
        every { appConfigRepository.isShareCardShowTimeEnabled() } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_TIME)
        every { appConfigRepository.isShareCardShowBrandEnabled() } returns flowOf(PreferenceDefaults.SHARE_CARD_SHOW_BRAND)
        every { appConfigRepository.getShareCardSignatureText() } returns flowOf(signatureText)
        every { appConfigRepository.isSyncInboxEnabled() } returns flowOf(PreferenceDefaults.SYNC_INBOX_ENABLED)
    }
}
