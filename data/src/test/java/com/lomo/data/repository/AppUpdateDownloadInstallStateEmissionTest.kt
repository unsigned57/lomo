package com.lomo.data.repository


import com.lomo.domain.model.AppUpdateInstallState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Test Contract:
 * - Unit under test: gateInstallPermissionBeforeDownload
 * - Behavior focus: in-app updates must require unknown-source install permission before any APK download work begins.
 * - Observable outcomes: emitted AppUpdateInstallState sequence and guarded download callback invocation.
 * - Red phase: Fails before the fix because the updater starts Preparing and download work before checking whether this app is allowed to install APKs.
 * - Excludes: APK network transport, Android package-manager APIs, and actual installer UI.
 */
class AppUpdateDownloadInstallStateEmissionTest : DataFunSpec() {
    init {
        test("gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work") { `gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work`() }

        test("gateInstallPermissionBeforeDownload allows download work when install permission is already granted") { `gateInstallPermissionBeforeDownload allows download work when install permission is already granted`() }
    }


    private fun `gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work`() =
        runTest {
            var downloadStarted = false

            val states =
                flow {
                    gateInstallPermissionBeforeDownload(
                        canRequestPackageInstalls = false,
                        missingPermissionMessage = "Allow installs from this app to continue.",
                    ) {
                        downloadStarted = true
                    }
                }.toList()

            states shouldBe listOf(
                    AppUpdateInstallState.RequiresInstallPermission(
                        message = "Allow installs from this app to continue.",
                    ),
                )
            (downloadStarted).shouldBeFalse()
        }

    private fun `gateInstallPermissionBeforeDownload allows download work when install permission is already granted`() =
        runTest {
            var downloadStarts = 0

            val states =
                flow {
                    gateInstallPermissionBeforeDownload(
                        canRequestPackageInstalls = true,
                        missingPermissionMessage = "unused",
                    ) {
                        downloadStarts += 1
                        emit(AppUpdateInstallState.Preparing)
                        emit(AppUpdateInstallState.Downloading(progress = 1))
                    }
                }.toList()

            states shouldBe listOf(
                    AppUpdateInstallState.Preparing,
                    AppUpdateInstallState.Downloading(progress = 1),
                )
            downloadStarts shouldBe 1
            (downloadStarts > 0).shouldBeTrue()
        }
}
