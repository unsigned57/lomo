package com.lomo.app.feature.preferences

import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.PreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: observeAppPreferences in AppPreferencesState.kt
 * - Behavior focus: aggregation of memo action auto-reorder settings into shared app preference state after quick-send removal.
 * - Observable outcomes: combined AppPreferencesState fields for the new auto-reorder flag and persisted action-id order.
 * - Red phase: Fails before the fix because the aggregated app preference state still depends on the removed quick-send contract.
 * - Excludes: screen-specific menu rendering, DataStore serialization, and unrelated settings groups.
 */
class AppPreferencesMemoActionStateTest {
    @Test
    fun `observeAppPreferences includes memo action ordering preferences`() =
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
            every { preferencesRepository.getMemoActionOrder() } returns flowOf(listOf("history", "copy"))

            val state = preferencesRepository.observeAppPreferences().first()

            assertEquals(true, state.memoActionAutoReorderEnabled)
            assertEquals(listOf("history", "copy"), state.memoActionOrder)
        }
}
