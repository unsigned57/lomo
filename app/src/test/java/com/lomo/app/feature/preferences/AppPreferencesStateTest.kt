package com.lomo.app.feature.preferences

import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.PreferencesRepository
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
            val preferencesRepository = mockk<PreferencesRepository>()
            every { preferencesRepository.getDateFormat() } returns flowOf("yyyy-MM-dd")
            every { preferencesRepository.getTimeFormat() } returns flowOf("HH:mm")
            every { preferencesRepository.getThemeMode() } returns flowOf(ThemeMode.DARK)
            every { preferencesRepository.isHapticFeedbackEnabled() } returns flowOf(false)
            every { preferencesRepository.isShowInputHintsEnabled() } returns flowOf(true)
            every { preferencesRepository.isDoubleTapEditEnabled() } returns flowOf(false)
            every { preferencesRepository.getShareCardStyle() } returns flowOf(ShareCardStyle.CLEAN)
            every { preferencesRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { preferencesRepository.isShareCardShowBrandEnabled() } returns flowOf(false)

            val state = preferencesRepository.observeAppPreferences().first()

            assertEquals(
                AppPreferencesState(
                    dateFormat = "yyyy-MM-dd",
                    timeFormat = "HH:mm",
                    themeMode = ThemeMode.DARK,
                    hapticFeedbackEnabled = false,
                    showInputHints = true,
                    doubleTapEditEnabled = false,
                    shareCardStyle = ShareCardStyle.CLEAN,
                    shareCardShowTime = true,
                    shareCardShowBrand = false,
                ),
                state,
            )
        }
}
