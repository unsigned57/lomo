/*
 * Behavior Contract:
 * - Unit under test: platform night-mode resolution for app-level theme synchronization.
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: determine the correct platform night mode setting based on app theme mode.
 *
 * Scenarios:
 * - Given ThemeMode.SYSTEM, when resolving platform night mode, then return UiModeManager.MODE_NIGHT_AUTO to clear explicit overrides.
 * - Given ThemeMode.LIGHT or ThemeMode.DARK, when resolving, then return UiModeManager.MODE_NIGHT_NO or UiModeManager.MODE_NIGHT_YES.
 *
 * Observable outcomes:
 * - resolved platform night mode integer.
 *
 * TDD proof:
 * - Compilation failure on Kotest transition - test-only migration; no production change.
 *
 * Excludes:
 * - AppCompatDelegate side effects, activity lifecycle timing, and OEM dark-mode propagation.
 */

package com.lomo.app.theme

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import android.app.UiModeManager
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.ThemeMode
import io.kotest.matchers.shouldBe

class AppNightModePolicyTest : AppFunSpec() {
    init {
        test("system mode clears previous explicit platform override") {
            (resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM)) shouldBe (UiModeManager.MODE_NIGHT_AUTO)
        }

        test("explicit theme modes keep explicit platform values") {
            (resolvePlatformNightMode(themeMode = ThemeMode.LIGHT)) shouldBe (UiModeManager.MODE_NIGHT_NO)
            (resolvePlatformNightMode(themeMode = ThemeMode.DARK)) shouldBe (UiModeManager.MODE_NIGHT_YES)
        }
    }
}
