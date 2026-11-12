package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.ThemeMode
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: theme preference summaries reflect pure-black appearance; owner: app; priority: P2.
 * Scenarios:
 * - Given pure black is enabled with SYSTEM or DARK, when resolving the summary, then its pure-black label is returned.
 * - Given LIGHT or disabled pure black, when resolving the summary, then the standard theme label is returned.
 * Observable outcomes: the resolved label for each theme and pure-black setting.
 * TDD proof: contract-only documentation repair; executable tests and assertions are unchanged.
 * Validation: final app host suite and working-tree meaningful-test checks.
 * Excludes: Compose rendering and system dark-mode detection.
 * Test Change Justification:
 * Reason category: non-behavior contract metadata repair.
 * Old behavior/assertion being replaced: obsolete Test Contract metadata without required sections; no assertion changes.
 * Why old assertion is no longer correct: the old header cannot describe the required Given/When/Then evidence.
 * Coverage preserved by: all existing theme and pure-black label assertions remain unchanged.
 * Why this is not fitting the test to the implementation: only documentation is updated to describe the existing cases.
 */
class ThemePreferenceLabelsTest : AppFunSpec() {
    init {
        val labels =
            mapOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色模式",
                ThemeMode.DARK to "深色模式",
            )
        val pureBlackSystem = "跟随系统（纯黑）"
        val pureBlackDark = "深色模式（纯黑）"

        test("resolves pure black label when theme is SYSTEM and pureBlackDark is enabled") {
            resolveThemeModeSummaryLabel(
                themeMode = ThemeMode.SYSTEM,
                pureBlackDark = true,
                themeModeLabels = labels,
                pureBlackSystemLabel = pureBlackSystem,
                pureBlackDarkLabel = pureBlackDark,
            ) shouldBe pureBlackSystem
        }

        test("resolves pure black label when theme is DARK and pureBlackDark is enabled") {
            resolveThemeModeSummaryLabel(
                themeMode = ThemeMode.DARK,
                pureBlackDark = true,
                themeModeLabels = labels,
                pureBlackSystemLabel = pureBlackSystem,
                pureBlackDarkLabel = pureBlackDark,
            ) shouldBe pureBlackDark
        }

        test("resolves normal light label when theme is LIGHT even if pureBlackDark is enabled") {
            resolveThemeModeSummaryLabel(
                themeMode = ThemeMode.LIGHT,
                pureBlackDark = true,
                themeModeLabels = labels,
                pureBlackSystemLabel = pureBlackSystem,
                pureBlackDarkLabel = pureBlackDark,
            ) shouldBe "浅色模式"
        }

        test("resolves default label when pureBlackDark is disabled") {
            resolveThemeModeSummaryLabel(
                themeMode = ThemeMode.SYSTEM,
                pureBlackDark = false,
                themeModeLabels = labels,
                pureBlackSystemLabel = pureBlackSystem,
                pureBlackDarkLabel = pureBlackDark,
            ) shouldBe "跟随系统"

            resolveThemeModeSummaryLabel(
                themeMode = ThemeMode.DARK,
                pureBlackDark = false,
                themeModeLabels = labels,
                pureBlackSystemLabel = pureBlackSystem,
                pureBlackDarkLabel = pureBlackDark,
            ) shouldBe "深色模式"
        }
    }
}
