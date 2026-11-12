package com.lomo.app.feature.preferences

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeAppConfigRepository
import com.lomo.app.testing.fakes.FakeCustomFontStore
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: observeAppPreferences state aggregation.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: combines all individual preference streams into a single clean app-preferences snapshot.
 *
 * Scenarios:
 * - Given all preference streams configured, when observeAppPreferences is observed, then emit a
 *   single consolidated snapshot matching all configurations, including foreground auto-input.
 *
 * Observable outcomes:
 * - Consolidated AppPreferencesState fields aggregate all values from the backing repository.
 *
 * TDD proof:
 * - RED: before auto-open input was part of AppPreferenceSnapshot/AppPreferencesState, the new
 *   aggregation assertion could not compile.
 *
 * Excludes:
 * - Compose rendering and repository implementation details.
 * Test Change Justification:
 * - Reason category: production API signature changed.
 * - Old behavior/assertion being replaced: observeAppPreferences took only the font store.
 * - Why old assertion is no longer correct: the unified CustomFontHost is now the loading surface, so the provider consumes it.
 * - Coverage preserved by: the same preference observation assertions through the new parameter.
 * - Why this is not fitting the test to the implementation: it reflects the unified font-loading contract.
 *
 * Production scope: apps/android/app/src/feature/preferences/AppPreferencesState.kt
 *
 * Test Change Justification:
 * - Reason category: product feature removal (usage-based memo menu auto-reorder).
 * - Old behavior/assertion being replaced: the aggregated state exposed the auto-reorder flag, the main
 *   memo action order and per-scope orders.
 * - Why old assertion is no longer correct: the feature and its preferences were deleted; the memo menu
 *   now has a fixed layout, so AppPreferencesState no longer carries these fields.
 * - Coverage preserved by: every other aggregated preference is still configured and asserted.
 * - Why this is not fitting the test to the implementation: only assertions for deleted product state
 *   were removed; no remaining field lost coverage.
 */
class AppPreferencesStateTest : AppFunSpec() {
    private val appConfigRepository = FakeAppConfigRepository()

    init {
        test("observeAppPreferences combines settings into single state") {
            runTest {
                appConfigRepository.setDateFormat("yyyy-MM-dd")
                appConfigRepository.setTimeFormat("HH:mm")
                appConfigRepository.setThemeModeNow(ThemeMode.DARK)
                val thresholds = CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9)
                appConfigRepository.setCalendarHeatmapThresholds(thresholds)
                appConfigRepository.setHapticFeedbackEnabled(false)
                appConfigRepository.setShowInputHintsEnabled(true)
                appConfigRepository.setDoubleTapEditEnabled(false)
                appConfigRepository.setFreeTextCopyEnabled(true)
                appConfigRepository.setQuickSaveOnBackEnabled(false)
                appConfigRepository.setScrollbarEnabled(true)
                appConfigRepository.setShareCardShowTime(true)
                appConfigRepository.setShareCardShowBrand(false)
                appConfigRepository.setShareCardSignatureText("Shared via Lomo")
                appConfigRepository.setFontSizeScale(PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE)
                appConfigRepository.setLineHeightScale(PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE)
                appConfigRepository.setLetterSpacingScale(PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE)
                appConfigRepository.setParagraphSpacingScale(PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE)
                appConfigRepository.setAutoOpenInputOnForegroundEnabled(true)
                appConfigRepository.updateInputToolbarToolOrder(listOf("backfill", "camera"))

                val state = appConfigRepository.observeAppPreferences(
                    FakeCustomFontStore(),
                    com.lomo.app.testing.fakes.testCustomFontHost(FakeCustomFontStore()),
                ).first()

                state.dateFormat shouldBe "yyyy-MM-dd"
                state.timeFormat shouldBe "HH:mm"
                state.themeMode shouldBe ThemeMode.DARK
                state.calendarHeatmapThresholds shouldBe thresholds
                state.hapticFeedbackEnabled shouldBe false
                state.showInputHints shouldBe true
                state.doubleTapEditEnabled shouldBe false
                state.freeTextCopyEnabled shouldBe true
                state.autoOpenInputOnForeground shouldBe true
                state.inputToolbarToolOrder shouldBe listOf("backfill", "camera")
                state.quickSaveOnBackEnabled shouldBe false
                state.scrollbarEnabled shouldBe true
                state.shareCardShowTime shouldBe true
                state.shareCardShowBrand shouldBe false
                state.shareCardSignatureText shouldBe "Shared via Lomo"
                state.typographyFontSizeScale shouldBe PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE
                state.typographyLineHeightScale shouldBe PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE
                state.typographyLetterSpacingScale shouldBe PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE
                state.typographyParagraphSpacingScale shouldBe PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE
            }
        }
    }
}
