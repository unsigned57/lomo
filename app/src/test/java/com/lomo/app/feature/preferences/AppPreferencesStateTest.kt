package com.lomo.app.feature.preferences

import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.PreferencesRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: observeAppPreferences state aggregation.
 * - Behavior focus: preference streams combine into a single app-preferences snapshot after quick-send mode is removed from shared app preferences.
 * - Observable outcomes: emitted AppPreferencesState value.
 * - Red phase: Fails before the fix because shared app preferences still require and aggregate the removed quick-send mode contract.
 * - Excludes: Compose rendering and repository implementation details.
 */
class AppPreferencesStateTest : AppFunSpec() {
    init {
        test("observeAppPreferences combines settings into single state") {
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
                every { preferencesRepository.isScrollbarEnabled() } returns flowOf(true)
                every { preferencesRepository.isShareCardShowTimeEnabled() } returns flowOf(true)
                every { preferencesRepository.isShareCardShowBrandEnabled() } returns flowOf(false)
                every { preferencesRepository.getShareCardSignatureText() } returns flowOf("Shared via Lomo")
                every { preferencesRepository.getFontSizeScale() } returns flowOf(PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE)
                every { preferencesRepository.getLineHeightScale() } returns flowOf(PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE)
                every { preferencesRepository.getLetterSpacingScale() } returns flowOf(PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE)
                every { preferencesRepository.getParagraphSpacingScale() } returns flowOf(PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE)
                every { preferencesRepository.isMemoActionAutoReorderEnabled() } returns flowOf(true)
                every {
                    preferencesRepository.getMemoActionOrder()
                } returns flowOf(listOf("history", "copy").toImmutableList())
                every {
                    preferencesRepository.getMemoActionOrdersByScope()
                } returns flowOf(mapOf(MemoActionOrderScopes.SEARCH to listOf("jump", "copy")))
                every {
                    preferencesRepository.getInputToolbarToolOrder()
                } returns flowOf(listOf("backfill", "camera").toImmutableList())

                val state = preferencesRepository.observeAppPreferences().first()

                (state) shouldBe (AppPreferencesState(
                        dateFormat = "yyyy-MM-dd",
                        timeFormat = "HH:mm",
                        themeMode = ThemeMode.DARK,
                        hapticFeedbackEnabled = false,
                        showInputHints = true,
                        doubleTapEditEnabled = false,
                        freeTextCopyEnabled = true,
                        memoActionAutoReorderEnabled = true,
                        memoActionOrder = listOf("history", "copy").toImmutableList(),
                        memoActionOrdersByScope =
                            mapOf(
                                MemoActionOrderScopes.SEARCH to listOf("jump", "copy").toImmutableList(),
                            ).toImmutableMap(),
                        inputToolbarToolOrder = listOf("backfill", "camera").toImmutableList(),
                        quickSaveOnBackEnabled = false,
                        scrollbarEnabled = true,
                        shareCardShowTime = true,
                        shareCardShowBrand = false,
                        shareCardSignatureText = "Shared via Lomo",
                        typographyFontSizeScale = PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE,
                        typographyLineHeightScale = PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE,
                        typographyLetterSpacingScale = PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE,
                        typographyParagraphSpacingScale = PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
                    ))
            }
        }
    }

}
