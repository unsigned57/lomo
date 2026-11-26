package com.lomo.app.feature.update

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.AppUpdateInstallState
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

class AppUpdateProgressPresentationTest : AppFunSpec() {
    init {
        test("downloading state resolves emphasized determinate progress presentation") {
            val presentation = AppUpdateInstallState.Downloading(progress = 42).toUpdateProgressPresentation()

            presentation.title shouldBe UpdateProgressTitle.Downloading
            presentation.tone shouldBe UpdateProgressTone.Progress
            presentation.progressPercent shouldBe 42
            presentation.showsDeterminateProgress shouldBe true
            presentation.supportingMessage shouldBe
                UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Downloading)
        }

        test("preparing state keeps indeterminate structure and hides percent") {
            val presentation = AppUpdateInstallState.Preparing.toUpdateProgressPresentation()

            presentation.title shouldBe UpdateProgressTitle.Preparing
            presentation.tone shouldBe UpdateProgressTone.Neutral
            presentation.progressPercent shouldBe null
            presentation.showsDeterminateProgress shouldBe false
            presentation.supportingMessage shouldBe
                UpdateProgressSupportingMessage.Default(UpdateProgressMessageKey.Preparing)
        }

        test("failed state switches to error emphasis and uses runtime message") {
            val presentation =
                AppUpdateInstallState.Failed(message = "Network lost").toUpdateProgressPresentation()

            presentation.title shouldBe UpdateProgressTitle.Failed
            presentation.tone shouldBe UpdateProgressTone.Error
            presentation.progressPercent shouldBe null
            presentation.showsDeterminateProgress shouldBe false
            presentation.supportingMessage shouldBe UpdateProgressSupportingMessage.Raw("Network lost")
        }

        test("completed and permission states never expose stale download metrics") {
            val completed = AppUpdateInstallState.Completed.toUpdateProgressPresentation()
            val requiresPermission =
                AppUpdateInstallState.RequiresInstallPermission(
                    message = "Allow unknown app installs to continue.",
                ).toUpdateProgressPresentation()

            completed.title shouldBe UpdateProgressTitle.Completed
            completed.tone shouldBe UpdateProgressTone.Success
            completed.progressPercent shouldBe null
            completed.showsDeterminateProgress shouldBe false

            requiresPermission.title shouldBe UpdateProgressTitle.RequiresInstallPermission
            requiresPermission.tone shouldBe UpdateProgressTone.Progress
            requiresPermission.progressPercent shouldBe null
            requiresPermission.showsDeterminateProgress shouldBe false
            requiresPermission.supportingMessage shouldBe
                UpdateProgressSupportingMessage.Raw("Allow unknown app installs to continue.")
        }

        test("resolveUpdateProgressHazeSpec derives blur, lighter haze scrim, and theme glass surface colors") {
            val lightScheme = lightColorScheme()
            val darkScheme = darkColorScheme()

            listOf(lightScheme, darkScheme).forEach { scheme ->
                val spec = resolveUpdateProgressHazeSpec(scheme)

                (spec.scrimBlurRadius > 0.dp) shouldBe true
                spec.scrimTintColor.alpha shouldBeGreaterThan 0f
                spec.scrimTintColor.alpha shouldBeLessThan spec.scrimFallbackTintColor.alpha
                spec.cardSurfaceColor shouldBe scheme.surfaceContainer
                spec.cardGlassTintColor.rgbEquals(scheme.surfaceContainer) shouldBe true
                spec.cardGlassTintColor.alpha shouldBeGreaterThan 0f
                spec.cardGlassTintColor.alpha shouldBeLessThan 1f
            }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.rgbEquals(other: androidx.compose.ui.graphics.Color): Boolean =
    red == other.red && green == other.green && blue == other.blue
