package com.lomo.app.theme

import android.app.UiModeManager
import com.lomo.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: platform night-mode resolution for app-level theme synchronization.
 * - Behavior focus: when the user returns from an explicit light/dark choice to following the
 *   system theme, the app must clear the platform-level application night override left by the
 *   explicit mode instead of leaving the app pinned to that old value.
 * - Observable outcomes: system mode resolves to the platform automatic mode that clears an
 *   explicit YES/NO application override, while explicit user light/dark choices still force
 *   explicit platform values.
 * - Red phase: Fails before the fix because ThemeMode.SYSTEM resolves to null, so applyAppNightMode
 *   returns without clearing the previously persisted application night-mode override.
 * - Excludes: AppCompatDelegate side effects, activity lifecycle timing, and OEM dark-mode propagation.
 */
/*
 * Test Change Justification:
 * - Reason category: old assertion was incomplete for the explicit-to-system transition.
 * - Exact behavior/assertion being replaced: ThemeMode.SYSTEM previously asserted that no platform
 *   mode should be written.
 * - Why the previous assertion is no longer correct: doing nothing is safe only when no previous app
 *   override exists. After the user manually chooses light/dark, Android 12+ keeps that application
 *   override until the app writes a non-explicit mode.
 * - Retained/new coverage: explicit light/dark mappings stay covered, and system mode now protects
 *   the reported transition from manual theme back to follow-system.
 * - Why this is not changing the test to fit the implementation: the assertion is based on the
 *   user-visible contract that follow-system must actually resume tracking system changes.
 */
class AppNightModePolicyTest {
    @Test
    fun `system mode clears previous explicit platform override`() {
        assertEquals(
            UiModeManager.MODE_NIGHT_AUTO,
            resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `explicit theme modes keep explicit platform values`() {
        assertEquals(
            UiModeManager.MODE_NIGHT_NO,
            resolvePlatformNightMode(themeMode = ThemeMode.LIGHT),
        )
        assertEquals(
            UiModeManager.MODE_NIGHT_YES,
            resolvePlatformNightMode(themeMode = ThemeMode.DARK),
        )
    }
}
