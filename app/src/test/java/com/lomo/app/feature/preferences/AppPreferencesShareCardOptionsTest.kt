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
 * - Behavior focus: share-card signature text remains aggregated into shared app preferences after recorded-days removal.
 * - Observable outcomes: emitted AppPreferencesState field for signature text.
 * - Red phase: Fails before the fix because observeAppPreferences still requires the removed recorded-days preference to build the share-card state.
 * - Test Change Justification: reason category = product contract changed; the old assertion expected a recorded-days toggle in app preferences, but that preference was explicitly removed. Coverage is preserved by keeping the signature-text aggregation check, which still protects cross-screen image-share configuration. This is not fitting the test to the implementation because the product requirement deleted the recorded-days option.
 * - Excludes: Compose rendering, repository persistence internals, and share-card bitmap drawing.
 */
class AppPreferencesShareCardOptionsTest {
    @Test
    fun `observeAppPreferences includes share card signature text`() =
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
            every { preferencesRepository.isShareCardShowBrandEnabled() } returns flowOf(true)
            every { preferencesRepository.getShareCardSignatureText() } returns flowOf("Unsigned57")
            every { preferencesRepository.isScrollbarEnabled() } returns flowOf(true)
            every { preferencesRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
            every {
                preferencesRepository.getMemoActionOrder()
            } returns flowOf(listOf("history", "copy").toImmutableList())

            val state = preferencesRepository.observeAppPreferences().first()

            assertEquals("Unsigned57", state.shareCardSignatureText)
        }
}
