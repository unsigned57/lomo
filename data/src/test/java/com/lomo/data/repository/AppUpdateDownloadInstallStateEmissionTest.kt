package com.lomo.data.repository

import com.lomo.domain.model.AppUpdateInstallState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: gateInstallPermissionBeforeDownload
 * - Behavior focus: in-app updates must require unknown-source install permission before any APK download work begins.
 * - Observable outcomes: emitted AppUpdateInstallState sequence and guarded download callback invocation.
 * - Red phase: Fails before the fix because the updater starts Preparing and download work before checking whether this app is allowed to install APKs.
 * - Excludes: APK network transport, Android package-manager APIs, and actual installer UI.
 */
class AppUpdateDownloadInstallStateEmissionTest {
    @Test
    fun `gateInstallPermissionBeforeDownload emits install-permission recovery state before any download work`() =
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

            assertEquals(
                listOf(
                    AppUpdateInstallState.RequiresInstallPermission(
                        message = "Allow installs from this app to continue.",
                    ),
                ),
                states,
            )
            assertFalse(downloadStarted)
        }

    @Test
    fun `gateInstallPermissionBeforeDownload allows download work when install permission is already granted`() =
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

            assertEquals(
                listOf(
                    AppUpdateInstallState.Preparing,
                    AppUpdateInstallState.Downloading(progress = 1),
                ),
                states,
            )
            assertEquals(1, downloadStarts)
            assertTrue(downloadStarts > 0)
        }
}
