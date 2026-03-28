package com.lomo.domain.usecase

import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: CheckStartupAppUpdateUseCase
 * - Behavior focus: startup update gating, force-update override, semantic version comparison, and fallback comparison for malformed versions.
 * - Observable outcomes: returned AppUpdateInfo or null, preserved release notes, and repository call gating when startup checks are disabled.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: repository transport implementation, UI presentation, and release-note rendering.
 */
class CheckStartupAppUpdateUseCaseTest {
    @Test
    fun `invoke keeps raw release notes without presentation normalization`() =
        runTest {
            val preferencesRepository = mockk<PreferencesRepository>()
            val appUpdateRepository = mockk<AppUpdateRepository>()
            val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

            every { preferencesRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(true)
            coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "0.1.0"

            val rawNotes = "[FORCE_UPDATE]\r\n" + "A".repeat(3500)
            coEvery { appUpdateRepository.fetchLatestRelease() } returns
                LatestAppRelease(
                    tagName = "v9.0.0",
                    htmlUrl = "https://example.com/release",
                    body = rawNotes,
                )

            val result =
                CheckStartupAppUpdateUseCase(
                    preferencesRepository = preferencesRepository,
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ).invoke()

            assertNotNull(result)
            assertEquals(rawNotes, result?.releaseNotes)
        }

    @Test
    fun `invoke returns null and skips fetch when startup update checks are disabled`() =
        runTest {
            val preferencesRepository = mockk<PreferencesRepository>()
            val appUpdateRepository = mockk<AppUpdateRepository>(relaxed = true)
            val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>(relaxed = true)

            every { preferencesRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(false)

            val result =
                CheckStartupAppUpdateUseCase(
                    preferencesRepository = preferencesRepository,
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ).invoke()

            assertNull(result)
        }

    @Test
    fun `invoke returns null when remote version is not newer and no force marker is present`() =
        runTest {
            val preferencesRepository = mockk<PreferencesRepository>()
            val appUpdateRepository = mockk<AppUpdateRepository>()
            val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

            every { preferencesRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(true)
            coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "1.2.3-debug"
            coEvery { appUpdateRepository.fetchLatestRelease() } returns
                LatestAppRelease(
                    tagName = "v1.2.3",
                    htmlUrl = "https://example.com/release",
                    body = "No force update",
                )

            val result =
                CheckStartupAppUpdateUseCase(
                    preferencesRepository = preferencesRepository,
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ).invoke()

            assertNull(result)
        }

    @Test
    fun `invoke returns update info when remote semantic version is newer`() =
        runTest {
            val preferencesRepository = mockk<PreferencesRepository>()
            val appUpdateRepository = mockk<AppUpdateRepository>()
            val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

            every { preferencesRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(true)
            coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "1.0.0"
            coEvery { appUpdateRepository.fetchLatestRelease() } returns
                LatestAppRelease(
                    tagName = "v1.1.0",
                    htmlUrl = "https://example.com/release",
                    body = "new release",
                )

            val result =
                CheckStartupAppUpdateUseCase(
                    preferencesRepository = preferencesRepository,
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ).invoke()

            assertNotNull(result)
            assertEquals("1.1.0", result?.version)
        }
}
