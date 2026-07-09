package com.lomo.app.feature.preferences

import com.lomo.app.feature.common.MemoActionOrderScopes
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
                appConfigRepository.setMemoActionAutoReorderEnabled(true)
                appConfigRepository.setAutoOpenInputOnForegroundEnabled(true)
                appConfigRepository.setMemoActionOrder(order = listOf("history", "copy"))
                appConfigRepository.setMemoActionOrder(
                    scope = MemoActionOrderScopes.SEARCH,
                    order = listOf("jump", "copy"),
                )
                appConfigRepository.updateInputToolbarToolOrder(listOf("backfill", "camera"))

                val state = appConfigRepository.observeAppPreferences(FakeCustomFontStore()).first()

                state.dateFormat shouldBe "yyyy-MM-dd"
                state.timeFormat shouldBe "HH:mm"
                state.themeMode shouldBe ThemeMode.DARK
                state.calendarHeatmapThresholds shouldBe thresholds
                state.hapticFeedbackEnabled shouldBe false
                state.showInputHints shouldBe true
                state.doubleTapEditEnabled shouldBe false
                state.freeTextCopyEnabled shouldBe true
                state.memoActionAutoReorderEnabled shouldBe true
                state.autoOpenInputOnForeground shouldBe true
                state.memoActionOrder shouldBe listOf("history", "copy")
                state.memoActionOrderFor(MemoActionOrderScopes.SEARCH) shouldBe listOf("jump", "copy")
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
