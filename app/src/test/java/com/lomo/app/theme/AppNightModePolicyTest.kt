package com.lomo.app.theme

import android.app.UiModeManager
import com.lomo.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: platform night-mode resolution for app-level theme synchronization.
 * - Behavior focus: when the user follows the system theme, the app must avoid writing any platform-level
 *   application night-mode override that would pin the app to a sampled snapshot instead of future system
 *   light/dark transitions.
 * - Observable outcomes: system mode resolves to no platform override, and explicit user light/dark choices
 *   still force explicit platform values.
 * - Red phase: Fails before the fix because ThemeMode.SYSTEM currently resolves to a concrete platform mode,
 *   which causes applyAppNightMode to persist an application-level override and break future follow-system
 *   changes.
 * - Excludes: AppCompatDelegate side effects, activity lifecycle timing, and OEM dark-mode propagation.
 */
/*
 * Test Change Justification:
 * - Reason category: old assertion was factually wrong.
 * - Exact behavior/assertion being replaced: ThemeMode.SYSTEM previously asserted that the current platform
 *   night mode should be mirrored into an app-level override.
 * - Why the previous assertion is no longer correct: on Android 12+, setApplicationNightMode persists an
 *   application override, so mirroring the current platform mode freezes follow-system into a snapshot and
 *   reproduces the user-visible bug.
 * - Retained/new coverage: explicit light/dark mappings stay covered, and the new system-mode assertions
 *   protect against writing direct YES/NO or scheduled AUTO/CUSTOM platform overrides while following system.
 * - Why this is not changing the test to fit the implementation: the revised assertion matches the reported
 *   broken behavior and the intended product contract that system mode must keep tracking later system changes.
 */
class AppNightModePolicyTest {
    @Test
    fun `system mode does not write explicit light platform mode`() {
        assertNull(
            resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `system mode does not write explicit dark platform mode`() {
        assertNull(
            resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `system mode does not write automatic platform schedule`() {
        assertNull(
            resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `system mode does not write custom platform schedule`() {
        assertNull(
            resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `system mode ignores unknown platform modes`() {
        assertNull(resolvePlatformNightMode(themeMode = ThemeMode.SYSTEM))
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
