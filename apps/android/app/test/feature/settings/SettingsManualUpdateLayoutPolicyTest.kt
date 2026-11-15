package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateDialogState
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given different manual-update row states, when layout policy is resolved, then the subtitle reserves the
 *   same line count.
 * - Unit under test: SettingsManualUpdateLayoutPolicy
 * - Capability: manual-update preference subtitle reserves a stable number of supporting-text lines across inline status transitions.
 * - Observable outcomes: subtitle min-line policy returned for representative manual update states.
 * - TDD proof: Fails before the fix because the manual-update settings row has no dedicated layout policy to keep its subtitle height stable across state changes.
 * - Excludes: Compose text measurement internals, Material3 ListItem rendering, and network update-check behavior.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for SettingsManualUpdateLayoutPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including manual update row keeps the same subtitle line
 * reservation across states.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class SettingsManualUpdateLayoutPolicyTest : AppFunSpec() {
    init {
        test("manual update row keeps the same subtitle line reservation across states") {
            val states =
                listOf(
                    SettingsManualUpdateState.Idle,
                    SettingsManualUpdateState.Checking,
                    SettingsManualUpdateState.UpToDate,
                    SettingsManualUpdateState.UpdateAvailable(
                        dialogState =
                        AppUpdateDialogState(
                            url = "https://example.com/releases/1.0.0",
                            version = "1.0.0",
                            releaseNotes = "notes",
                        ),
                    ),
                    SettingsManualUpdateState.Error("network timeout"),
                )

            val reservedLines = states.map(::manualUpdateSubtitleMinLines)

            (reservedLines) shouldBe (listOf(2, 2, 2, 2, 2))
        }
    }
}
