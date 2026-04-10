package com.lomo.app.feature.update

import com.lomo.domain.model.AppUpdateInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: update progress presentation mapping for the app-layer in-app update dialog.
 * - Behavior focus: install states must project into a readable dialog hierarchy with explicit stage
 *   titles, correct progress emphasis, and state-appropriate supporting text.
 * - Observable outcomes: resolved presentation title, tone, determinate-progress visibility, exposed
 *   percent value, and whether supporting text comes from default copy or the runtime state message.
 * - Red phase: Fails before the fix because the dialog encodes its presentation directly in Compose,
 *   exposes no contract for readable stage hierarchy, and therefore cannot guarantee that download,
 *   permission, and failure states map to distinct readable treatments.
 * - Excludes: Compose drawing, backdrop shader rendering, downloader transport, and package installer UI.
 */
class AppUpdateProgressPresentationTest {
    @Test
    fun `downloading state resolves emphasized determinate progress presentation`() {
        val presentation = AppUpdateInstallState.Downloading(progress = 42).toUpdateProgressPresentation()

        assertEquals(UpdateProgressTitle.Downloading, presentation.title)
        assertEquals(UpdateProgressTone.Progress, presentation.tone)
        assertEquals(42, presentation.progressPercent)
        assertTrue(presentation.showsDeterminateProgress)
        assertEquals(
            UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Downloading),
            presentation.supportingMessage,
        )
    }

    @Test
    fun `preparing state keeps indeterminate structure and hides percent`() {
        val presentation = AppUpdateInstallState.Preparing.toUpdateProgressPresentation()

        assertEquals(UpdateProgressTitle.Preparing, presentation.title)
        assertEquals(UpdateProgressTone.Neutral, presentation.tone)
        assertEquals(null, presentation.progressPercent)
        assertFalse(presentation.showsDeterminateProgress)
        assertEquals(
            UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Preparing),
            presentation.supportingMessage,
        )
    }

    @Test
    fun `failed state switches to error emphasis and uses runtime message`() {
        val presentation =
            AppUpdateInstallState.Failed(message = "Network lost").toUpdateProgressPresentation()

        assertEquals(UpdateProgressTitle.Failed, presentation.title)
        assertEquals(UpdateProgressTone.Error, presentation.tone)
        assertEquals(null, presentation.progressPercent)
        assertFalse(presentation.showsDeterminateProgress)
        assertEquals(UpdateProgressSupportingMessage.Raw("Network lost"), presentation.supportingMessage)
    }

    @Test
    fun `completed and permission states never expose stale download metrics`() {
        val completed = AppUpdateInstallState.Completed.toUpdateProgressPresentation()
        val requiresPermission =
            AppUpdateInstallState.RequiresInstallPermission(
                message = "Allow unknown app installs to continue.",
            ).toUpdateProgressPresentation()

        assertEquals(UpdateProgressTitle.Completed, completed.title)
        assertEquals(UpdateProgressTone.Success, completed.tone)
        assertEquals(null, completed.progressPercent)
        assertFalse(completed.showsDeterminateProgress)

        assertEquals(UpdateProgressTitle.RequiresInstallPermission, requiresPermission.title)
        assertEquals(UpdateProgressTone.Progress, requiresPermission.tone)
        assertEquals(null, requiresPermission.progressPercent)
        assertFalse(requiresPermission.showsDeterminateProgress)
        assertEquals(
            UpdateProgressSupportingMessage.Raw("Allow unknown app installs to continue."),
            requiresPermission.supportingMessage,
        )
    }
}
