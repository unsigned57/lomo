package com.lomo.ui.theme

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: effective dark-theme resolution for LomoTheme.
 * - Behavior focus: when the host activity already knows the current uiMode, ThemeMode.SYSTEM must use that effective night mask instead of waiting for Compose's ambient system-dark signal to catch up.
 * - Observable outcomes: explicit light/dark decisions for explicit theme modes and current-ui-driven decisions for system mode.
 * - Red phase: Fails before the fix because LomoTheme relies only on isSystemInDarkTheme() for ThemeMode.SYSTEM, so a resumed activity can render one stale frame before Compose observes the updated system dark state.
 * - Excludes: AppCompatDelegate integration, activity lifecycle callbacks, and color animation policy.
 */
class ThemeDarkResolutionTest {
    @Test
    fun `system theme prefers current ui mode over stale fallback`() {
        assertTrue(
            resolveDarkTheme(
                themeMode = ThemeMode.SYSTEM,
                currentUiMode = Configuration.UI_MODE_NIGHT_YES,
                systemDarkTheme = false,
            ),
        )
        assertFalse(
            resolveDarkTheme(
                themeMode = ThemeMode.SYSTEM,
                currentUiMode = Configuration.UI_MODE_NIGHT_NO,
                systemDarkTheme = true,
            ),
        )
    }

    @Test
    fun `system theme falls back when ui mode is undefined`() {
        assertTrue(
            resolveDarkTheme(
                themeMode = ThemeMode.SYSTEM,
                currentUiMode = Configuration.UI_MODE_NIGHT_UNDEFINED,
                systemDarkTheme = true,
            ),
        )
    }

    @Test
    fun `explicit themes ignore current ui mode`() {
        assertFalse(
            resolveDarkTheme(
                themeMode = ThemeMode.LIGHT,
                currentUiMode = Configuration.UI_MODE_NIGHT_YES,
                systemDarkTheme = true,
            ),
        )
        assertTrue(
            resolveDarkTheme(
                themeMode = ThemeMode.DARK,
                currentUiMode = Configuration.UI_MODE_NIGHT_NO,
                systemDarkTheme = false,
            ),
        )
    }
}
