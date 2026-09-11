package com.lomo.app.feature.update

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.AppUpdateInstallState
import io.kotest.matchers.shouldBe

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
class AppUpdateProgressPresentationTest : AppFunSpec() {
    init {
        test("downloading state resolves emphasized determinate progress presentation") {
            val presentation = AppUpdateInstallState.Downloading(progress = 42).toUpdateProgressPresentation()

            (presentation.title) shouldBe (UpdateProgressTitle.Downloading)
            (presentation.tone) shouldBe (UpdateProgressTone.Progress)
            (presentation.progressPercent) shouldBe (42)
            ((presentation.showsDeterminateProgress)) shouldBe true
            (presentation.supportingMessage) shouldBe (UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Downloading))
        }
    }

    init {
        test("preparing state keeps indeterminate structure and hides percent") {
            val presentation = AppUpdateInstallState.Preparing.toUpdateProgressPresentation()

            (presentation.title) shouldBe (UpdateProgressTitle.Preparing)
            (presentation.tone) shouldBe (UpdateProgressTone.Neutral)
            (presentation.progressPercent) shouldBe (null)
            ((presentation.showsDeterminateProgress)) shouldBe false
            (presentation.supportingMessage) shouldBe (UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Preparing))
        }
    }

    init {
        test("failed state switches to error emphasis and uses runtime message") {
            val presentation =
                AppUpdateInstallState.Failed(message = "Network lost").toUpdateProgressPresentation()

            (presentation.title) shouldBe (UpdateProgressTitle.Failed)
            (presentation.tone) shouldBe (UpdateProgressTone.Error)
            (presentation.progressPercent) shouldBe (null)
            ((presentation.showsDeterminateProgress)) shouldBe false
            (presentation.supportingMessage) shouldBe (UpdateProgressSupportingMessage.Raw("Network lost"))
        }
    }

    init {
        test("completed and permission states never expose stale download metrics") {
            val completed = AppUpdateInstallState.Completed.toUpdateProgressPresentation()
            val requiresPermission =
                AppUpdateInstallState.RequiresInstallPermission(
                    message = "Allow unknown app installs to continue.",
                ).toUpdateProgressPresentation()

            (completed.title) shouldBe (UpdateProgressTitle.Completed)
            (completed.tone) shouldBe (UpdateProgressTone.Success)
            (completed.progressPercent) shouldBe (null)
            ((completed.showsDeterminateProgress)) shouldBe false

            (requiresPermission.title) shouldBe (UpdateProgressTitle.RequiresInstallPermission)
            (requiresPermission.tone) shouldBe (UpdateProgressTone.Progress)
            (requiresPermission.progressPercent) shouldBe (null)
            ((requiresPermission.showsDeterminateProgress)) shouldBe false
            (requiresPermission.supportingMessage) shouldBe (UpdateProgressSupportingMessage.Raw("Allow unknown app installs to continue."))
        }
    }

}
