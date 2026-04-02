package com.lomo.app.feature.settings

import com.lomo.app.feature.update.AppUpdateDialogState
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: SettingsManualUpdateLayoutPolicy
 * - Behavior focus: manual-update preference subtitle reserves a stable number of supporting-text lines across inline status transitions.
 * - Observable outcomes: subtitle min-line policy returned for representative manual update states.
 * - Red phase: Fails before the fix because the manual-update settings row has no dedicated layout policy to keep its subtitle height stable across state changes.
 * - Excludes: Compose text measurement internals, Material3 ListItem rendering, and network update-check behavior.
 */
class SettingsManualUpdateLayoutPolicyTest {
    @Test
    fun `manual update row keeps the same subtitle line reservation across states`() {
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

        assertEquals(listOf(2, 2, 2, 2, 2), reservedLines)
    }
}
