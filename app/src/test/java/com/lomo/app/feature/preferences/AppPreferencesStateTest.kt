package com.lomo.app.feature.preferences

import com.lomo.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesStateTest {
    @Test
    fun `observeAppPreferences combines settings into single state`() =
        runTest {
            val settingsRepository = mockk<SettingsRepository>()
            every { settingsRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
            every { settingsRepository.getTimeFormat() } returns flowOf("HH:mm")
            every { settingsRepository.getThemeMode() } returns flowOf("dark")
            every { settingsRepository.isHapticFeedbackEnabled() } returns flowOf(false)
            every { settingsRepository.isShowInputHintsEnabled() } returns flowOf(true)
            every { settingsRepository.isDoubleTapEditEnabled() } returns flowOf(false)
            every { settingsRepository.getShareCardStyle() } returns flowOf("clean")
            every { settingsRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { settingsRepository.isShareCardShowBrandEnabled() } returns flowOf(false)

            val state = settingsRepository.observeAppPreferences().first()

            assertEquals(
                AppPreferencesState(
                    dateFormat = "yyyy-MM-dd",
                    timeFormat = "HH:mm",
                    themeMode = "dark",
                    hapticFeedbackEnabled = false,
                    showInputHints = true,
                    doubleTapEditEnabled = false,
                    shareCardStyle = "clean",
                    shareCardShowTime = true,
                    shareCardShowBrand = false,
                ),
                state,
            )
        }
}
