package com.lomo.app.feature.preferences

import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.PreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: observeAppPreferences state aggregation.
 * - Behavior focus: preference streams combine into a single app-preferences snapshot after quick-send mode is removed from shared app preferences.
 * - Observable outcomes: emitted AppPreferencesState value.
 * - Red phase: Fails before the fix because shared app preferences still require and aggregate the removed quick-send mode contract.
 * - Excludes: Compose rendering and repository implementation details.
 */
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
            every { preferencesRepository.isFreeTextCopyEnabled() } returns flowOf(true)
            every { preferencesRepository.isQuickSaveOnBackEnabled() } returns flowOf(false)
            every { preferencesRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
            every { preferencesRepository.isShareCardShowBrandEnabled() } returns flowOf(false)
            every { preferencesRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every {
                preferencesRepository.getMemoActionOrder()
            } returns flowOf(listOf("history", "copy").toImmutableList())

            val state = preferencesRepository.observeAppPreferences().first()

            assertEquals(
                AppPreferencesState(
                    dateFormat = "yyyy-MM-dd",
                    timeFormat = "HH:mm",
                    themeMode = ThemeMode.DARK,
                    hapticFeedbackEnabled = false,
                    showInputHints = true,
                    doubleTapEditEnabled = false,
                    freeTextCopyEnabled = true,
                    memoActionAutoReorderEnabled = true,
                    memoActionOrder = listOf("history", "copy").toImmutableList(),
                    quickSaveOnBackEnabled = false,
                    shareCardShowTime = true,
                    shareCardShowBrand = false,
                ),
                state,
            )
        }
}
