package com.lomo.app.theme

import android.content.res.Configuration
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.ThemeMode
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: theme resync policy for lifecycle resume and handled uiMode changes.
 * - Behavior focus: when the user follows the system theme, Lomo must re-sync app night mode on handled uiMode transitions without relying on a visible resume-time correction; explicit light/dark choices must remain sticky.
 * - Observable outcomes: boolean resync decisions for resume and uiMode-change events.
 * - Red phase: Fails before the fix because the policy still asks MainActivity to re-sync on resume, which causes a visible foreground theme flip after the app becomes visible.
 * - Excludes: AppCompatDelegate side effects, OEM dark-mode rendering, and Compose recomposition details.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract changed.
 * - Exact behavior/assertion being replaced: resume under ThemeMode.SYSTEM previously asserted that a re-sync should occur.
 * - Why the previous assertion is no longer correct: the desired behavior is now to finish theme correction while backgrounded via handled configuration changes and avoid a visible on-resume flash.
 * - Retained/new coverage: the config-change assertions still protect system-theme following, and the new resume assertion protects the no-foreground-flip requirement.
 * - Why this is not changing the test to fit the implementation: the user explicitly rejected the visible resume-time transition, so the observable requirement changed.
 */
class ThemeResyncPolicyTest : AppFunSpec() {
    init {
        test("resume does not request resync even when following system theme") {
            ((ThemeResyncPolicy.shouldResyncOnResume(ThemeMode.SYSTEM))) shouldBe false
        }
    }

    init {
        test("resume does not resync when user explicitly chose a theme") {
            ((ThemeResyncPolicy.shouldResyncOnResume(ThemeMode.LIGHT))) shouldBe false
            ((ThemeResyncPolicy.shouldResyncOnResume(ThemeMode.DARK))) shouldBe false
        }
    }

    init {
        test("uiMode change requests resync only for real night-mode transitions while following system") {
            ((ThemeResyncPolicy.shouldResyncOnConfigurationChange(
                    themeMode = ThemeMode.SYSTEM,
                    previousUiMode = Configuration.UI_MODE_NIGHT_NO,
                    currentUiMode = Configuration.UI_MODE_NIGHT_YES,
                ))) shouldBe true
            ((ThemeResyncPolicy.shouldResyncOnConfigurationChange(
                    themeMode = ThemeMode.SYSTEM,
                    previousUiMode = Configuration.UI_MODE_NIGHT_YES,
                    currentUiMode = Configuration.UI_MODE_NIGHT_YES,
                ))) shouldBe false
            ((ThemeResyncPolicy.shouldResyncOnConfigurationChange(
                    themeMode = ThemeMode.DARK,
                    previousUiMode = Configuration.UI_MODE_NIGHT_NO,
                    currentUiMode = Configuration.UI_MODE_NIGHT_YES,
                ))) shouldBe false
        }
    }

}
